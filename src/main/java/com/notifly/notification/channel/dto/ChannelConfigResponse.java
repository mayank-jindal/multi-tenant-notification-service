package com.notifly.notification.channel.dto;

import com.notifly.notification.channel.TenantChannelConfig;
import com.notifly.notification.common.model.Channel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A channel's configuration.
 *
 * <p>Carries no credential material. {@code credentialsSet} and {@code credentialsHint} answer
 * "is one configured, and which" without returning the value itself, so a stolen read token
 * cannot be used to exfiltrate a tenant's provider keys.
 *
 * @param id              the configuration row's id
 * @param channel         which channel this configures
 * @param enabled         whether the channel accepts sends
 * @param configured      whether the channel is usable — enabled and holding any credential it needs
 * @param senderIdentity  the identity the provider sends as
 * @param credentialsSet  whether a credential is stored
 * @param credentialsHint masked tail of the stored credential, never the full value
 * @param providerCode    which provider adapter handles this channel
 * @param maxInFlight     per-channel concurrency ceiling, null if unset
 * @param updatedAt       when the configuration last changed
 */
@Schema(description = "A tenant's configuration for one channel")
public record ChannelConfigResponse(
        UUID id,
        String channel,
        boolean enabled,
        boolean configured,
        String senderIdentity,
        boolean credentialsSet,
        String credentialsHint,
        String providerCode,
        Integer maxInFlight,
        Instant updatedAt) {

    public static ChannelConfigResponse from(TenantChannelConfig config) {
        return new ChannelConfigResponse(
                config.getId(),
                config.getChannel().name(),
                config.isEnabled(),
                config.isEnabled(),
                config.getSenderIdentity(),
                config.hasCredentials(),
                config.getCredentialsHint(),
                config.getProviderCode(),
                config.getMaxInFlight(),
                config.getUpdatedAt());
    }

    /** Placeholder for a channel the tenant has never configured. */
    public static ChannelConfigResponse unconfigured(Channel channel) {
        return new ChannelConfigResponse(
                null, channel.name(), false, false, null, false, null, null, null, null);
    }
}
