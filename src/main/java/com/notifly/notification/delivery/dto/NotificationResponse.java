package com.notifly.notification.delivery.dto;

import com.notifly.notification.delivery.Notification;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One notification's current state.
 *
 * @param id                the notification's id
 * @param requestId         the submission it came from
 * @param channel           which channel it is delivered on
 * @param recipient         the destination address
 * @param recipientRef      the tenant's identifier for the recipient
 * @param status            current lifecycle state
 * @param attemptCount      how many provider attempts have been made
 * @param maxAttempts       the retry budget
 * @param nextAttemptAt     when it next becomes claimable
 * @param scheduledAt       when it was scheduled for, null for immediate sends
 * @param sentAt            when a provider accepted it
 * @param deliveredAt       when delivery was confirmed
 * @param lastErrorCode     failure code from the most recent attempt
 * @param lastErrorMessage  failure detail from the most recent attempt
 * @param providerMessageId the provider's identifier for the message
 * @param createdAt         when it was created
 */
@Schema(description = "A notification and its delivery state")
public record NotificationResponse(
        UUID id,
        UUID requestId,
        String channel,
        String recipient,
        String recipientRef,
        String status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant scheduledAt,
        Instant sentAt,
        Instant deliveredAt,
        String lastErrorCode,
        String lastErrorMessage,
        String providerMessageId,
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getRequestId(),
                notification.getChannel().name(),
                notification.getRecipientAddress(),
                notification.getRecipientRef(),
                notification.getStatus().name(),
                notification.getAttemptCount(),
                notification.getMaxAttempts(),
                notification.getNextAttemptAt(),
                notification.getScheduledAt(),
                notification.getSentAt(),
                notification.getDeliveredAt(),
                notification.getLastErrorCode(),
                notification.getLastErrorMessage(),
                notification.getProviderMessageId(),
                notification.getCreatedAt());
    }
}
