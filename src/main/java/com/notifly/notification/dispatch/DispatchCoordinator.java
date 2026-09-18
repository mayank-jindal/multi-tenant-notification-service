package com.notifly.notification.dispatch;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.common.config.DispatchProperties;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.delivery.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives delivery: promotes scheduled work, claims what is due, and hands it to the pools.
 *
 * <p>A database-backed queue rather than a broker, because the brief puts distributed systems out
 * of scope. The trade is deliberate and is stated in the README: throughput is bounded by
 * Postgres rather than by Kafka, and in exchange a submission and its queue entry commit in one
 * transaction — a notification can never be accepted and then lost — and the entire delivery
 * lifecycle is queryable in SQL.
 *
 * <p>Each cycle, per channel: find which active tenants have due work, ask the fairness selector
 * how to divide the available capacity, then claim each tenant's share with
 * {@code FOR UPDATE SKIP LOCKED}. Capacity is read from the pool itself, so the dispatcher never
 * claims more than it can actually run — claimed work that could not be submitted would sit
 * leased and idle until the lease expired.
 */
@Component
@ConditionalOnProperty(prefix = "notifly.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DispatchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DispatchCoordinator.class);

    private final NotificationClaimRepository claimRepository;
    private final FairnessSelector fairnessSelector;
    private final ChannelWorkerPools workerPools;
    private final NotificationDispatcher dispatcher;
    private final DispatchProperties properties;
    private final AuditService auditService;

    /** Identifies this instance in a lease, so an abandoned lease can be traced to its owner. */
    private final String leaseOwner;

    /** Advances every cycle so the fairness rotation moves and no tenant is starved by position. */
    private final AtomicLong rotation = new AtomicLong();

    public DispatchCoordinator(NotificationClaimRepository claimRepository,
                               FairnessSelector fairnessSelector,
                               ChannelWorkerPools workerPools,
                               NotificationDispatcher dispatcher,
                               DispatchProperties properties,
                               AuditService auditService) {
        this.claimRepository = claimRepository;
        this.fairnessSelector = fairnessSelector;
        this.workerPools = workerPools;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.auditService = auditService;
        this.leaseOwner = resolveLeaseOwner();
    }

    /**
     * One dispatch cycle across every channel.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}: a slow cycle must not have the next one
     * start on top of it. With fixedRate, a cycle that overruns its interval would overlap
     * itself, and two coordinators claiming at once is exactly the contention the design avoids
     * everywhere else.
     */
    @Scheduled(fixedDelayString = "${notifly.dispatch.poll-interval-ms:500}")
    public void dispatchCycle() {
        long cycle = rotation.incrementAndGet();
        for (Channel channel : Channel.values()) {
            try {
                dispatchChannel(channel, cycle);
            } catch (RuntimeException ex) {
                // One channel failing must not stop the others. Without this, a bad query on the
                // email channel would silently halt SMS, push and in-app as well.
                log.error("Dispatch cycle failed for channel {}", channel, ex);
            }
        }
    }

    private void dispatchChannel(Channel channel, long cycle) {
        // Capacity comes from the pool, not from configuration: claiming work the pool cannot
        // accept would leave it leased and idle until the lease expired.
        int capacity = Math.min(properties.batchSize(), workerPools.availableCapacity(channel));
        if (capacity <= 0) {
            return;
        }

        Instant now = Instant.now();
        List<TenantWorkload> workloads = claimRepository.findWorkloads(channel, now);
        if (workloads.isEmpty()) {
            return;
        }

        Map<UUID, Integer> allocation = fairnessSelector.allocate(
                workloads, capacity, properties.maxInFlightPerTenant(), cycle);

        for (Map.Entry<UUID, Integer> entry : allocation.entrySet()) {
            UUID tenantId = entry.getKey();
            int slots = entry.getValue();

            Instant leaseExpiry = Instant.now().plusSeconds(properties.leaseSeconds());
            List<UUID> claimed = claimRepository.claimBatch(
                    channel, tenantId, slots, leaseOwner, UUID.randomUUID(), Instant.now(), leaseExpiry);

            for (UUID notificationId : claimed) {
                boolean accepted = workerPools.submit(channel,
                        () -> dispatcher.dispatch(tenantId, notificationId));

                if (!accepted) {
                    // The pool filled between the capacity check and now. The notification stays
                    // leased; its lease expires and the reaper requeues it. Stopping here rather
                    // than continuing avoids claiming more work we cannot run.
                    log.debug("Pool for {} saturated mid-cycle; {} remains leased pending recovery",
                            channel, notificationId);
                    return;
                }
            }
        }
    }

    /**
     * Promotes scheduled notifications whose time has arrived.
     *
     * <p>Runs on its own schedule rather than inside the dispatch cycle so that a saturated
     * channel does not also stall the clock — a scheduled send should become due on time even
     * when the pool is busy.
     */
    @Scheduled(fixedDelayString = "${notifly.dispatch.poll-interval-ms:500}")
    public void promoteScheduled() {
        try {
            int promoted = claimRepository.promoteDueScheduled(Instant.now(), properties.batchSize() * 10);
            if (promoted > 0) {
                log.debug("Promoted {} scheduled notifications to the queue", promoted);
            }
        } catch (RuntimeException ex) {
            log.error("Failed to promote scheduled notifications", ex);
        }
    }

    /**
     * Returns work abandoned by a crashed worker.
     *
     * <p>Runs a tenth as often as the dispatch cycle. Lease expiry is measured in tens of seconds,
     * so checking every half second would be almost entirely wasted queries.
     */
    @Scheduled(fixedDelayString = "#{${notifly.dispatch.poll-interval-ms:500} * 10}")
    public void reclaimExpiredLeases() {
        try {
            List<UUID> reclaimed = claimRepository.reclaimExpiredLeases(Instant.now(), 100);
            if (reclaimed.isEmpty()) {
                return;
            }

            // The attempt rows those workers left open are closed as ABANDONED rather than
            // deleted: the provider may or may not have received the message, and that ambiguity
            // is exactly what the history needs to record.
            claimRepository.abandonInFlightAttempts(reclaimed);

            log.warn("Recovered {} notifications from expired leases", reclaimed.size());
            for (UUID notificationId : reclaimed) {
                auditService.recordSystemAction(AuditEventType.NOTIFICATION_LEASE_EXPIRED,
                        "Notification", notificationId, null,
                        Map.of("recoveredBy", leaseOwner,
                               "note", "Attempt may have reached the provider; delivery is at-least-once"));
            }
        } catch (RuntimeException ex) {
            log.error("Failed to reclaim expired leases", ex);
        }
    }

    /** Current pool occupancy, for the concurrency test and for operational visibility. */
    public Map<String, Integer> poolStatus() {
        return java.util.Arrays.stream(Channel.values())
                .collect(java.util.stream.Collectors.toMap(
                        Channel::name, workerPools::activeCount));
    }

    /** Statuses the dispatcher treats as claimable, exposed so tests assert against one source. */
    public static List<NotificationStatus> claimableStatuses() {
        return List.of(NotificationStatus.QUEUED, NotificationStatus.RETRY_SCHEDULED);
    }

    private String resolveLeaseOwner() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception ex) {
            // The hostname is a nicety for tracing; a pid alone still identifies the owner.
            return "dispatcher-" + ProcessHandle.current().pid();
        }
    }
}
