package com.notifly.notification.delivery;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.common.dto.PageResponse;
import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.delivery.dto.NotificationResponse;
import com.notifly.notification.delivery.dto.SendResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reading delivery state, and cancelling work that has not yet been dispatched.
 */
@Service
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final NotificationRequestRepository requestRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final AuditService auditService;

    public NotificationQueryService(NotificationRepository notificationRepository,
                                    NotificationRequestRepository requestRepository,
                                    DeliveryAttemptRepository attemptRepository,
                                    AuditService auditService) {
        this.notificationRepository = notificationRepository;
        this.requestRepository = requestRepository;
        this.attemptRepository = attemptRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public NotificationResponse get(UUID notificationId) {
        return NotificationResponse.from(require(notificationId));
    }

    /**
     * Filtered delivery search.
     *
     * <p>Built with a specification rather than a finder per filter combination: with status,
     * channel, template and a date range all optional, named methods would multiply out into
     * something nobody could maintain.
     */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> search(NotificationStatus status,
                                                     Channel channel,
                                                     UUID requestId,
                                                     Instant from,
                                                     Instant to,
                                                     Pageable pageable) {
        UUID tenantId = TenantContext.requireTenantId();

        Specification<Notification> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Redundant with the tenant discriminator, and kept anyway: the scoping is then
            // visible in the query itself rather than implied by configuration elsewhere.
            predicates.add(cb.equal(root.get("tenantId"), tenantId));

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (channel != null) {
                predicates.add(cb.equal(root.get("channel"), channel));
            }
            if (requestId != null) {
                predicates.add(cb.equal(root.get("requestId"), requestId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Notification> page = notificationRepository.findAll(spec, pageable);
        return PageResponse.of(page, NotificationResponse::from);
    }

    /** The full attempt history of one notification — how it reached its current state. */
    @Transactional(readOnly = true)
    public List<DeliveryAttemptResponse> attempts(UUID notificationId) {
        require(notificationId);
        return attemptRepository.findAllByNotificationIdOrderByAttemptNumberAsc(notificationId).stream()
                .map(DeliveryAttemptResponse::from)
                .toList();
    }

    /** Counts by status and by channel, which is what a delivery report needs to open with. */
    @Transactional(readOnly = true)
    public DeliverySummary summary() {
        UUID tenantId = TenantContext.requireTenantId();

        Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
        for (NotificationStatus status : NotificationStatus.values()) {
            long count = notificationRepository.countByTenantIdAndStatus(tenantId, status);
            if (count > 0) {
                byStatus.put(status.name(), count);
            }
        }

        Map<String, Long> channelCounts = new java.util.LinkedHashMap<>();
        for (Channel channel : Channel.values()) {
            long count = notificationRepository.countByTenantIdAndChannel(tenantId, channel);
            if (count > 0) {
                channelCounts.put(channel.name(), count);
            }
        }

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long delivered = byStatus.getOrDefault(NotificationStatus.DELIVERED.name(), 0L)
                         + byStatus.getOrDefault(NotificationStatus.SENT.name(), 0L);
        long failed = byStatus.getOrDefault(NotificationStatus.FAILED.name(), 0L);

        return new DeliverySummary(total, delivered, failed, byStatus, channelCounts);
    }

    /**
     * Cancels a notification that has not yet been handed to a provider.
     *
     * <p>Cancellation is only meaningful before dispatch. Once a provider has accepted the
     * message it is gone, and pretending otherwise would be a lie told by the API.
     */
    @Transactional
    public NotificationResponse cancel(UUID notificationId) {
        Notification notification = require(notificationId);

        if (!notification.getStatus().isCancellable()) {
            throw Errors.conflict(ErrorCode.NOT_CANCELLABLE,
                            "A notification in state " + notification.getStatus() + " can no longer be cancelled")
                    .with("status", notification.getStatus().name());
        }

        NotificationStatus previous = notification.getStatus();
        notification.transitionTo(NotificationStatus.CANCELLED, Instant.now());
        notificationRepository.save(notification);

        auditService.recordTransition(notification.getTenantId(), notificationId,
                previous.name(), NotificationStatus.CANCELLED.name(), Map.of("cancelledBy", "TENANT_ADMIN"));

        return NotificationResponse.from(notification);
    }

    /** Cancels every still-cancellable notification in a submission. */
    @Transactional
    public SendResponse cancelRequest(UUID requestId) {
        UUID tenantId = TenantContext.requireTenantId();
        NotificationRequest request = requestRepository.findByIdAndTenantId(requestId, tenantId)
                .orElseThrow(() -> Errors.notFound("NotificationRequest", requestId));

        Instant now = Instant.now();
        int cancelled = 0;
        for (Notification notification : notificationRepository.findAllByRequestId(requestId)) {
            if (!notification.getStatus().isCancellable()) {
                continue;
            }
            NotificationStatus previous = notification.getStatus();
            notification.transitionTo(NotificationStatus.CANCELLED, now);
            notificationRepository.save(notification);
            auditService.recordTransition(tenantId, notification.getId(),
                    previous.name(), NotificationStatus.CANCELLED.name(), Map.of("bulk", true));
            cancelled++;
        }

        request.setStatus(NotificationRequestStatus.CANCELLED);
        requestRepository.save(request);
        auditService.record(AuditEventType.REQUEST_CANCELLED, "NotificationRequest", requestId,
                Map.of("cancelled", cancelled));

        return new SendResponse(requestId, request.getStatus().name(), request.getScheduledAt(),
                cancelled, 0, 0, null, false);
    }

    /** Rebuilds the response for an earlier submission, used to answer an idempotent replay. */
    @Transactional(readOnly = true)
    public SendResponse describeRequest(UUID requestId, boolean replay) {
        UUID tenantId = TenantContext.requireTenantId();
        NotificationRequest request = requestRepository.findByIdAndTenantId(requestId, tenantId)
                .orElseThrow(() -> Errors.notFound("NotificationRequest", requestId));

        List<Notification> notifications = notificationRepository.findAllByRequestId(requestId);

        return new SendResponse(
                requestId,
                request.getStatus().name(),
                request.getScheduledAt(),
                notifications.size(),
                0,
                0,
                notifications.size() <= SendResponse.INLINE_LIMIT
                        ? notifications.stream().map(NotificationResponse::from).toList()
                        : null,
                replay);
    }

    private Notification require(UUID notificationId) {
        UUID tenantId = TenantContext.requireTenantId();
        return notificationRepository.findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> Errors.notFound("Notification", notificationId));
    }

    /**
     * @param id             the attempt's id
     * @param attemptNumber  which attempt this was
     * @param outcome        result, or null if the attempt never completed
     * @param providerCode   which adapter handled it
     * @param errorCode      failure code, null on success
     * @param errorMessage   failure detail, null on success
     * @param latencyMs      how long the provider call took
     * @param startedAt      when the attempt began
     * @param completedAt    when it finished, null if it never did
     */
    public record DeliveryAttemptResponse(
            UUID id,
            int attemptNumber,
            String outcome,
            String providerCode,
            String errorCode,
            String errorMessage,
            Integer latencyMs,
            Instant startedAt,
            Instant completedAt) {

        static DeliveryAttemptResponse from(DeliveryAttempt attempt) {
            return new DeliveryAttemptResponse(
                    attempt.getId(),
                    attempt.getAttemptNumber(),
                    attempt.getOutcome() == null ? null : attempt.getOutcome().name(),
                    attempt.getProviderCode(),
                    attempt.getErrorCode(),
                    attempt.getErrorMessage(),
                    attempt.getLatencyMs(),
                    attempt.getStartedAt(),
                    attempt.getCompletedAt());
        }
    }

    /**
     * @param total     every notification the tenant has created
     * @param delivered sent or confirmed delivered
     * @param failed    permanently failed
     * @param byStatus  counts per lifecycle state
     * @param byChannel counts per channel
     */
    public record DeliverySummary(
            long total,
            long delivered,
            long failed,
            Map<String, Long> byStatus,
            Map<String, Long> byChannel) {
    }
}
