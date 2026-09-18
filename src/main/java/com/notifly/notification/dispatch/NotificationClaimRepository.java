package com.notifly.notification.dispatch;

import com.notifly.notification.common.model.Channel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The dispatcher's claim path, in native SQL.
 *
 * <p>Separate from {@code NotificationRepository} because the locking semantics are the whole
 * point and belong next to the code that depends on them.
 *
 * <p>Two properties matter here and neither is expressible through JPA:
 *
 * <ul>
 *   <li><strong>{@code FOR UPDATE SKIP LOCKED}</strong> — concurrent workers claiming from the
 *       same channel step over each other's locked rows instead of blocking on them. Without it,
 *       every worker would serialise behind the first, and the pool's concurrency would be
 *       decorative.</li>
 *   <li><strong>Claim and lease in one statement</strong> — the {@code UPDATE ... RETURNING}
 *       selects, locks and stamps the lease atomically. Selecting and then updating would leave a
 *       window in which a crash loses the rows entirely, or two workers both believe they own
 *       them.</li>
 * </ul>
 *
 * <p>These queries are deliberately unscoped by tenant: the dispatcher works across all tenants
 * and decides allocation itself. Native SQL bypasses the {@code @TenantId} discriminator, which
 * is what makes that possible — and is why the tenant id is always passed explicitly rather than
 * being left to ambient context.
 */
@Repository
public class NotificationClaimRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Tenants with claimable work on a channel, with their weights and backlog sizes.
     *
     * <p>Joined to {@code tenants} so a suspended tenant's backlog is never considered — held,
     * as the suspension contract promises, rather than cancelled or dispatched.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<TenantWorkload> findWorkloads(Channel channel, Instant now) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT n.tenant_id, t.dispatch_weight, COUNT(*) AS pending
                        FROM notifications n
                        JOIN tenants t ON t.id = n.tenant_id
                        WHERE t.status = 'ACTIVE'
                          AND n.channel = :channel
                          AND n.status IN ('QUEUED', 'RETRY_SCHEDULED')
                          AND n.next_attempt_at <= :now
                        GROUP BY n.tenant_id, t.dispatch_weight
                        ORDER BY pending DESC
                        """)
                .setParameter("channel", channel.name())
                .setParameter("now", Timestamp.from(now))
                .getResultList();

        List<TenantWorkload> workloads = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            workloads.add(new TenantWorkload(
                    (UUID) row[0],
                    ((Number) row[1]).intValue(),
                    ((Number) row[2]).longValue()));
        }
        return workloads;
    }

    /**
     * Claims up to {@code limit} notifications for one tenant on one channel, stamping a lease.
     *
     * <p>Runs in its own transaction, committed before the claimed work is handed to the pool. A
     * worker must never begin sending inside the transaction that claimed the row: the lease has
     * to be durably visible to every other worker first, or two of them could hold the same row.
     *
     * @return the ids now leased to this worker
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<UUID> claimBatch(Channel channel,
                                 UUID tenantId,
                                 int limit,
                                 String leaseOwner,
                                 UUID leaseToken,
                                 Instant now,
                                 Instant leaseExpiresAt) {
        if (limit <= 0) {
            return List.of();
        }

        List<UUID> claimed = entityManager.createNativeQuery("""
                        UPDATE notifications
                        SET status = 'SENDING',
                            lease_token = :leaseToken,
                            lease_owner = :leaseOwner,
                            lease_expires_at = :leaseExpiresAt,
                            updated_at = now(),
                            version = version + 1
                        WHERE id IN (
                            SELECT id FROM notifications
                            WHERE channel = :channel
                              AND tenant_id = :tenantId
                              AND status IN ('QUEUED', 'RETRY_SCHEDULED')
                              AND next_attempt_at <= :now
                            ORDER BY priority ASC, next_attempt_at ASC
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        RETURNING id
                        """)
                .setParameter("leaseToken", leaseToken)
                .setParameter("leaseOwner", leaseOwner)
                .setParameter("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
                .setParameter("channel", channel.name())
                .setParameter("tenantId", tenantId)
                .setParameter("now", Timestamp.from(now))
                .setParameter("limit", limit)
                .getResultList();

        return claimed;
    }

    /**
     * Promotes scheduled notifications whose time has come.
     *
     * <p>A single set-based statement rather than loading and saving entities: a scheduled
     * campaign can be tens of thousands of rows, and pulling them all into the persistence
     * context to flip one column would be pure waste.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int promoteDueScheduled(Instant now, int limit) {
        return entityManager.createNativeQuery("""
                        UPDATE notifications
                        SET status = 'QUEUED',
                            next_attempt_at = LEAST(next_attempt_at, now()),
                            updated_at = now(),
                            version = version + 1
                        WHERE id IN (
                            SELECT id FROM notifications
                            WHERE status = 'SCHEDULED'
                              AND scheduled_at <= :now
                            ORDER BY scheduled_at ASC
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        """)
                .setParameter("now", Timestamp.from(now))
                .setParameter("limit", limit)
                .executeUpdate();
    }

    /**
     * Returns notifications whose lease has expired to the queue.
     *
     * <p>This is what stops a crashed worker from stranding work in {@code SENDING} forever. The
     * attempt row it left behind is closed separately as {@code ABANDONED}, so the history records
     * that the attempt happened and may have reached the provider.
     *
     * @return the ids that were recovered
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<UUID> reclaimExpiredLeases(Instant now, int limit) {
        return entityManager.createNativeQuery("""
                        UPDATE notifications
                        SET status = CASE
                                         WHEN attempt_count >= max_attempts THEN 'FAILED'
                                         ELSE 'QUEUED'
                                     END,
                            terminal_at = CASE
                                              WHEN attempt_count >= max_attempts THEN now()
                                              ELSE terminal_at
                                          END,
                            lease_token = NULL,
                            lease_owner = NULL,
                            lease_expires_at = NULL,
                            last_error_code = 'LEASE_EXPIRED',
                            last_error_message = 'Worker did not complete the attempt within its lease',
                            updated_at = now(),
                            version = version + 1
                        WHERE id IN (
                            SELECT id FROM notifications
                            WHERE status = 'SENDING'
                              AND lease_expires_at < :now
                            ORDER BY lease_expires_at ASC
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        RETURNING id
                        """)
                .setParameter("now", Timestamp.from(now))
                .setParameter("limit", limit)
                .getResultList();
    }

    /** Closes out attempt rows left in flight by a worker that died. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int abandonInFlightAttempts(List<UUID> notificationIds) {
        if (notificationIds.isEmpty()) {
            return 0;
        }
        return entityManager.createNativeQuery("""
                        UPDATE delivery_attempts
                        SET outcome = 'ABANDONED',
                            completed_at = now(),
                            error_code = 'LEASE_EXPIRED',
                            error_message = 'Worker did not report an outcome before the lease expired'
                        WHERE notification_id IN (:ids)
                          AND outcome IS NULL
                        """)
                .setParameter("ids", notificationIds)
                .executeUpdate();
    }
}
