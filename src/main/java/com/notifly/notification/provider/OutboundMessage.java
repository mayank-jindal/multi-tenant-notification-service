package com.notifly.notification.provider;

import com.notifly.notification.common.model.Channel;

import java.util.UUID;

/**
 * Everything a provider needs to deliver one message.
 *
 * <p>A flat value object rather than the {@code Notification} entity: an adapter has no business
 * reaching into persistence, and passing the entity would let one mutate delivery state behind
 * the dispatcher's back.
 *
 * @param notificationId  the notification being delivered, for correlation in provider logs
 * @param tenantId        the owning tenant
 * @param channel         which channel is being used
 * @param recipient       the channel-native destination
 * @param subject         rendered subject, or push title; null where the channel has none
 * @param bodyText        rendered plain-text body
 * @param bodyHtml        rendered HTML body, email only
 * @param senderIdentity  the configured identity to send as
 * @param credentials     decrypted provider credentials, null where the channel needs none
 * @param attemptNumber   which attempt this is, starting at 1
 */
public record OutboundMessage(
        UUID notificationId,
        UUID tenantId,
        Channel channel,
        String recipient,
        String subject,
        String bodyText,
        String bodyHtml,
        String senderIdentity,
        String credentials,
        int attemptNumber) {
}
