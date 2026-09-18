package com.notifly.notification.delivery;

import com.notifly.notification.common.model.Channel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for notifications.
 *
 * <p>The dispatcher's claim query is deliberately <em>not</em> here — it needs
 * {@code FOR UPDATE SKIP LOCKED} and native SQL, and lives with the dispatch engine so the
 * locking semantics sit next to the code that depends on them.
 *
 * <p>{@link JpaSpecificationExecutor} is extended so delivery search can compose the optional
 * filters (status, channel, date range, template) without a combinatorial explosion of finder
 * methods.
 */
public interface NotificationRepository
        extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    Optional<Notification> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Notification> findAllByRequestId(UUID requestId);

    Page<Notification> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    long countByRequestIdAndStatusIn(UUID requestId, List<NotificationStatus> statuses);

    long countByTenantIdAndStatus(UUID tenantId, NotificationStatus status);

    long countByTenantIdAndChannel(UUID tenantId, Channel channel);

    /**
     * Scheduled notifications whose time has come. Returned in schedule order so a backlog is
     * promoted oldest-first rather than arbitrarily.
     */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.status = com.notifly.notification.delivery.NotificationStatus.SCHEDULED
              AND n.scheduledAt <= :now
            ORDER BY n.scheduledAt ASC
            """)
    List<Notification> findDueScheduled(@Param("now") Instant now, Pageable pageable);

    /**
     * Rows whose dispatch lease has expired — their worker died mid-send. Recovering these is
     * what stops a crash from stranding work in SENDING forever.
     */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.status = com.notifly.notification.delivery.NotificationStatus.SENDING
              AND n.leaseExpiresAt < :now
            """)
    List<Notification> findExpiredLeases(@Param("now") Instant now, Pageable pageable);

    /** Tenants that currently have claimable work on a channel, for the fairness rotation. */
    @Query("""
            SELECT DISTINCT n.tenantId FROM Notification n
            WHERE n.channel = :channel
              AND n.status IN (com.notifly.notification.delivery.NotificationStatus.QUEUED,
                               com.notifly.notification.delivery.NotificationStatus.RETRY_SCHEDULED)
              AND n.nextAttemptAt <= :now
            """)
    List<UUID> findTenantsWithPendingWork(@Param("channel") Channel channel, @Param("now") Instant now);

    /** How many of a tenant's notifications are currently being sent, for the in-flight cap. */
    @Query("""
            SELECT COUNT(n) FROM Notification n
            WHERE n.tenantId = :tenantId
              AND n.channel = :channel
              AND n.status = com.notifly.notification.delivery.NotificationStatus.SENDING
            """)
    long countInFlight(@Param("tenantId") UUID tenantId, @Param("channel") Channel channel);

    /** In-app inbox for one recipient. */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.tenantId = :tenantId
              AND n.channel = com.notifly.notification.common.model.Channel.IN_APP
              AND n.recipientRef = :recipientRef
              AND n.status IN (com.notifly.notification.delivery.NotificationStatus.SENT,
                               com.notifly.notification.delivery.NotificationStatus.DELIVERED)
            ORDER BY n.createdAt DESC
            """)
    Page<Notification> findInbox(@Param("tenantId") UUID tenantId,
                                 @Param("recipientRef") String recipientRef,
                                 Pageable pageable);

    @Query("""
            SELECT COUNT(n) FROM Notification n
            WHERE n.tenantId = :tenantId
              AND n.channel = com.notifly.notification.common.model.Channel.IN_APP
              AND n.recipientRef = :recipientRef
              AND n.readAt IS NULL
              AND n.status IN (com.notifly.notification.delivery.NotificationStatus.SENT,
                               com.notifly.notification.delivery.NotificationStatus.DELIVERED)
            """)
    long countUnread(@Param("tenantId") UUID tenantId, @Param("recipientRef") String recipientRef);

    /** Unread in-app messages for one recipient, for the mark-all-read operation. */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.tenantId = :tenantId
              AND n.channel = com.notifly.notification.common.model.Channel.IN_APP
              AND n.recipientRef = :recipientRef
              AND n.readAt IS NULL
              AND n.status IN (com.notifly.notification.delivery.NotificationStatus.SENT,
                               com.notifly.notification.delivery.NotificationStatus.DELIVERED)
            """)
    List<Notification> findUnreadInApp(@Param("tenantId") UUID tenantId,
                                       @Param("recipientRef") String recipientRef);
}
