package com.notifly.notification.dispatch;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.channel.ChannelConfigService;
import com.notifly.notification.channel.TenantChannelConfig;
import com.notifly.notification.delivery.DeliveryAttempt;
import com.notifly.notification.delivery.DeliveryAttemptRepository;
import com.notifly.notification.delivery.Notification;
import com.notifly.notification.delivery.NotificationRepository;
import com.notifly.notification.delivery.NotificationStatus;
import com.notifly.notification.provider.ChannelProvider;
import com.notifly.notification.provider.OutboundMessage;
import com.notifly.notification.provider.ProviderRegistry;
import com.notifly.notification.provider.ProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The transactional halves of a dispatch attempt.
 *
 * <p>A separate bean from {@link NotificationDispatcher} for a specific reason rather than
 * tidiness: Spring's {@code @Transactional} is implemented with a proxy, so a method calling
 * another method on {@code this} bypasses it entirely and the annotation silently does nothing.
 * Keeping these two steps on a collaborator means they are genuinely invoked through the proxy
 * and genuinely transactional.
 *
 * <p>Each step is {@code REQUIRES_NEW} and short. Between them sits a network call to a third
 * party, and holding a database connection open across it is how one slow provider exhausts the
 * connection pool for everything else.
 */
@Service
public class AttemptRecorder {

    private static final Logger log = LoggerFactory.getLogger(AttemptRecorder.class);

    private final NotificationRepository notificationRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final ChannelConfigService channelConfigService;
    private final ProviderRegistry providerRegistry;
    private final BackoffCalculator backoffCalculator;
    private final AuditService auditService;

    public AttemptRecorder(NotificationRepository notificationRepository,
                           DeliveryAttemptRepository attemptRepository,
                           ChannelConfigService channelConfigService,
                           ProviderRegistry providerRegistry,
                           BackoffCalculator backoffCalculator,
                           AuditService auditService) {
        this.notificationRepository = notificationRepository;
        this.attemptRepository = attemptRepository;
        this.channelConfigService = channelConfigService;
        this.providerRegistry = providerRegistry;
        this.backoffCalculator = backoffCalculator;
        this.auditService = auditService;
    }

    /**
     * Records the attempt and assembles what the provider needs.
     *
     * <p>Committed before the provider is called, so that a process death mid-send still leaves
     * evidence the attempt happened. Writing the attempt afterwards would leave the system
     * believing the message was never tried, and the retry would be a genuine duplicate that
     * nothing recorded.
     *
     * <p>The lease token is verified, not just the status. A task can sit in the pool's queue
     * long enough for its lease to expire, be reclaimed by the reaper, and be claimed again by
     * another worker — at which point the row is back in {@code SENDING} and a status check alone
     * would let both workers send it. Comparing the token proves this task still owns the row.
     *
     * @return null when the notification is no longer dispatchable, in which case nothing is sent
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedAttempt beginAttempt(UUID notificationId, UUID expectedLeaseToken) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            log.warn("Claimed notification {} no longer exists", notificationId);
            return null;
        }

        // Cancelled between the claim and now, or already reaped. Either way it is not ours.
        if (notification.getStatus() != NotificationStatus.SENDING) {
            log.debug("Notification {} is {} and will not be dispatched",
                    notificationId, notification.getStatus());
            return null;
        }

        if (expectedLeaseToken != null && !expectedLeaseToken.equals(notification.getLeaseToken())) {
            // Our lease expired and the row was re-claimed by someone else. Sending now would be
            // a genuine duplicate, so this task stands down and lets the current owner proceed.
            log.warn("Lease for notification {} was taken over; standing down to avoid a duplicate send",
                    notificationId);
            return null;
        }

        TenantChannelConfig config;
        try {
            config = channelConfigService.requireUsable(
                    notification.getTenantId(), notification.getChannel());
        } catch (RuntimeException ex) {
            // The channel was disabled after this work was queued. Failing permanently is
            // correct: retrying cannot succeed while the configuration says not to send.
            failPermanently(notification, "CHANNEL_UNAVAILABLE", ex.getMessage());
            return null;
        }

        int attemptNumber = notification.beginAttempt();
        notificationRepository.save(notification);

        DeliveryAttempt savedAttempt = attemptRepository.save(new DeliveryAttempt(
                notification.getTenantId(), notificationId, attemptNumber, config.getProviderCode()));

        auditService.recordSystemAction(AuditEventType.NOTIFICATION_ATTEMPT_STARTED,
                "Notification", notificationId, notification.getTenantId(),
                Map.of("attempt", attemptNumber, "channel", notification.getChannel().name()));

        ChannelProvider provider = providerRegistry.forChannel(notification.getChannel());

        OutboundMessage message = new OutboundMessage(
                notificationId,
                notification.getTenantId(),
                notification.getChannel(),
                notification.getRecipientAddress(),
                notification.getRenderedSubject(),
                notification.getRenderedBodyText(),
                notification.getRenderedBodyHtml(),
                config.getSenderIdentity(),
                channelConfigService.decryptCredentials(config),
                attemptNumber);

        return new PreparedAttempt(savedAttempt.getId(), provider, message);
    }

    /** Applies the provider's verdict: success, a scheduled retry, or terminal failure. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAttempt(UUID notificationId, UUID attemptId, ProviderResult result) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return;
        }

        Instant now = Instant.now();

        attemptRepository.findById(attemptId).ifPresent(attempt -> {
            if (result.isSuccess()) {
                attempt.setProviderMessageId(result.providerMessageId());
                attempt.complete(result.outcome(), now);
            } else {
                attempt.completeWithError(result.outcome(), result.errorCode(), result.errorMessage(), now);
            }
            attemptRepository.save(attempt);
        });

        auditService.recordSystemAction(AuditEventType.NOTIFICATION_ATTEMPT_COMPLETED,
                "Notification", notificationId, notification.getTenantId(),
                Map.of("attempt", notification.getAttemptCount(),
                        "outcome", result.outcome().name(),
                        "errorCode", String.valueOf(result.errorCode())));

        if (result.isSuccess()) {
            succeed(notification, result, now);
            return;
        }

        notification.recordFailure(result.errorCode(), result.errorMessage());

        if (!result.outcome().isRetryable()) {
            // A permanent failure short-circuits the retry budget entirely. Spending four more
            // attempts on an address the provider has already rejected wastes capacity another
            // tenant's work could have used.
            failPermanently(notification, result.errorCode(), result.errorMessage());
            return;
        }

        if (!notification.hasRetriesRemaining()) {
            failPermanently(notification, result.errorCode(),
                    "Retry budget exhausted after " + notification.getAttemptCount() + " attempts");
            return;
        }

        scheduleRetry(notification, now);
    }

    private void succeed(Notification notification, ProviderResult result, Instant now) {
        notification.setProviderMessageId(result.providerMessageId());
        notification.releaseLease();

        NotificationStatus previous = notification.getStatus();
        notification.transitionTo(NotificationStatus.SENT, now);

        // In-app messages have no external provider and no asynchronous delivery receipt: writing
        // the row to our own store *is* delivery, so they advance immediately rather than waiting
        // for a confirmation that will never arrive.
        if (notification.getChannel().isInternal()) {
            notification.transitionTo(NotificationStatus.DELIVERED, now);
        }

        notificationRepository.save(notification);

        auditService.recordTransition(notification.getTenantId(), notification.getId(),
                previous.name(), notification.getStatus().name(),
                Map.of("providerMessageId", String.valueOf(result.providerMessageId()),
                        "attempts", notification.getAttemptCount()));
    }

    private void scheduleRetry(Notification notification, Instant now) {
        Instant nextAttempt = backoffCalculator.nextAttemptAt(notification.getAttemptCount());

        notification.releaseLease();
        notification.scheduleRetryAt(nextAttempt);

        NotificationStatus previous = notification.getStatus();
        notification.transitionTo(NotificationStatus.RETRY_SCHEDULED, now);
        notificationRepository.save(notification);

        auditService.recordSystemAction(AuditEventType.NOTIFICATION_RETRY_SCHEDULED,
                "Notification", notification.getId(), notification.getTenantId(),
                Map.of("attempt", notification.getAttemptCount(),
                        "nextAttemptAt", nextAttempt.toString(),
                        "errorCode", String.valueOf(notification.getLastErrorCode())));

        auditService.recordTransition(notification.getTenantId(), notification.getId(),
                previous.name(), NotificationStatus.RETRY_SCHEDULED.name(),
                Map.of("nextAttemptAt", nextAttempt.toString()));

        log.debug("Notification {} retry {} scheduled for {}",
                notification.getId(), notification.getAttemptCount(), nextAttempt);
    }

    private void failPermanently(Notification notification, String errorCode, String errorMessage) {
        Instant now = Instant.now();
        notification.recordFailure(errorCode, errorMessage);
        notification.releaseLease();

        NotificationStatus previous = notification.getStatus();
        notification.transitionTo(NotificationStatus.FAILED, now);
        notificationRepository.save(notification);

        auditService.recordTransition(notification.getTenantId(), notification.getId(),
                previous.name(), NotificationStatus.FAILED.name(),
                Map.of("errorCode", String.valueOf(errorCode),
                        "errorMessage", String.valueOf(errorMessage),
                        "attempts", notification.getAttemptCount()));

        auditService.recordSystemAction(AuditEventType.NOTIFICATION_DEAD_LETTERED,
                "Notification", notification.getId(), notification.getTenantId(),
                Map.of("errorCode", String.valueOf(errorCode),
                        "attempts", notification.getAttemptCount()));
    }

    /**
     * Everything a send needs, assembled inside a transaction so the provider call itself needs
     * no database access.
     */
    public record PreparedAttempt(UUID attemptId, ChannelProvider provider, OutboundMessage message) {
    }
}
