package com.notifly.notification.delivery;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.common.dto.PageResponse;
import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The in-app message inbox.
 *
 * <p>IN_APP is the one channel with no third party in it: "delivery" means the row became visible
 * in the recipient's inbox, so without a way to read that inbox the channel is only half a
 * feature. This is the other half.
 *
 * <p><strong>Who calls this.</strong> Recipients are not users of this service — they are the
 * tenant's customers, and they have no accounts here. So the tenant's own backend reads the inbox
 * on a recipient's behalf, authenticated as a tenant admin and naming the recipient. Inventing
 * accounts for every recipient would mean this service owning the tenant's user directory, which
 * is a much larger and quite different product.
 *
 * <p>Because the recipient reference is a parameter rather than an identity, the tenant scope is
 * what stops one tenant reading another's inbox — the discriminator applies here exactly as
 * everywhere else.
 */
@Service
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class InboxService {

    private final NotificationRepository notificationRepository;
    private final AuditService auditService;

    public InboxService(NotificationRepository notificationRepository, AuditService auditService) {
        this.notificationRepository = notificationRepository;
        this.auditService = auditService;
    }

    /**
     * Messages delivered to one recipient, newest first.
     *
     * <p>Only messages that actually reached the recipient are returned. A queued, failed or
     * cancelled notification has not been delivered, and showing it in an inbox would be showing
     * someone a message that was never sent to them.
     */
    @Transactional(readOnly = true)
    public PageResponse<InboxMessage> inbox(String recipientRef, Pageable pageable) {
        UUID tenantId = TenantContext.requireTenantId();
        requireRecipient(recipientRef);

        return PageResponse.of(
                notificationRepository.findInbox(tenantId, recipientRef, pageable),
                InboxMessage::from);
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(String recipientRef) {
        UUID tenantId = TenantContext.requireTenantId();
        requireRecipient(recipientRef);

        return new UnreadCount(recipientRef,
                notificationRepository.countUnread(tenantId, recipientRef));
    }

    /**
     * Marks one message read.
     *
     * <p>Idempotent, and deliberately does not overwrite an existing timestamp: the first read is
     * the interesting fact, and a second call should not rewrite history. That rule lives on the
     * entity, so no caller can get it wrong.
     */
    @Transactional
    public InboxMessage markRead(UUID notificationId) {
        UUID tenantId = TenantContext.requireTenantId();

        Notification notification = notificationRepository
                .findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> Errors.notFound("Notification", notificationId));

        if (notification.getChannel() != Channel.IN_APP) {
            throw Errors.badRequest(ErrorCode.VALIDATION_FAILED,
                            "Only in-app messages can be marked read")
                    .with("channel", notification.getChannel().name());
        }

        boolean alreadyRead = notification.getReadAt() != null;
        notification.markRead(Instant.now());
        notificationRepository.save(notification);

        // Audited only on the transition, not on every repeated call, so a client polling the
        // inbox does not bury the trail in duplicates.
        if (!alreadyRead) {
            auditService.recordSystemAction(AuditEventType.NOTIFICATION_READ,
                    "Notification", notificationId, tenantId,
                    Map.of("recipientRef", String.valueOf(notification.getRecipientRef())));
        }

        return InboxMessage.from(notification);
    }

    /** Marks every unread message for a recipient as read. */
    @Transactional
    public UnreadCount markAllRead(String recipientRef) {
        UUID tenantId = TenantContext.requireTenantId();
        requireRecipient(recipientRef);

        Instant now = Instant.now();
        int marked = 0;

        for (Notification notification :
                notificationRepository.findUnreadInApp(tenantId, recipientRef)) {
            notification.markRead(now);
            notificationRepository.save(notification);
            marked++;
        }

        if (marked > 0) {
            auditService.recordSystemAction(AuditEventType.NOTIFICATION_READ,
                    "Notification", null, tenantId,
                    Map.of("recipientRef", recipientRef, "markedRead", marked));
        }

        return new UnreadCount(recipientRef, 0);
    }

    private void requireRecipient(String recipientRef) {
        if (recipientRef == null || recipientRef.isBlank()) {
            throw Errors.badRequest(ErrorCode.VALIDATION_FAILED, "recipientRef is required");
        }
    }

    /**
     * One message as the recipient sees it.
     *
     * <p>Carries the rendered content and nothing operational — no attempt counts, lease tokens or
     * provider identifiers. Those are the sender's concern, and a recipient-facing payload should
     * not leak how the platform works.
     *
     * @param id        the message id, used to mark it read
     * @param subject   rendered subject, if the template had one
     * @param body      rendered message text
     * @param read      whether it has been read
     * @param readAt    when it was first read, null if unread
     * @param deliveredAt when it arrived
     */
    @Schema(description = "An in-app message as its recipient sees it")
    public record InboxMessage(
            UUID id,
            String subject,
            String body,
            boolean read,
            Instant readAt,
            Instant deliveredAt) {

        static InboxMessage from(Notification notification) {
            return new InboxMessage(
                    notification.getId(),
                    notification.getRenderedSubject(),
                    notification.getRenderedBodyText(),
                    notification.getReadAt() != null,
                    notification.getReadAt(),
                    notification.getDeliveredAt() != null
                            ? notification.getDeliveredAt()
                            : notification.getSentAt());
        }
    }

    /**
     * @param recipientRef the recipient this count is for
     * @param unread       how many delivered messages remain unread
     */
    @Schema(description = "Unread message count for one recipient")
    public record UnreadCount(String recipientRef, long unread) {
    }
}
