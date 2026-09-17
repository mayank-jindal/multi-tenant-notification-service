package com.notifly.notification.channel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * A partial update of one channel's configuration. Omitted fields are left unchanged.
 *
 * @param enabled        whether the channel accepts sends
 * @param senderIdentity the address or sender id the provider sends as
 * @param credentials    provider credentials; write-only and never returned
 * @param providerCode   which provider adapter handles this channel
 * @param maxInFlight    per-channel ceiling on this tenant's concurrent sends
 */
@Schema(description = "Channel configuration changes; omitted fields are left alone")
public record UpdateChannelConfigRequest(

        Boolean enabled,

        @Schema(example = "no-reply@acme.com",
                description = "An email address for EMAIL, a sender id or long code for SMS, an app id for PUSH")
        @Size(max = 255, message = "must be at most 255 characters")
        String senderIdentity,

        @Schema(description = "Write-only. Stored AES-256-GCM encrypted and never returned; responses carry a masked hint instead.")
        @Size(max = 4000, message = "must be at most 4000 characters")
        String credentials,

        @Schema(example = "SIMULATOR")
        @Size(max = 64, message = "must be at most 64 characters")
        String providerCode,

        @Schema(description = "Caps this tenant's concurrent sends on this channel. Null removes the override.")
        @Min(value = 1, message = "must be at least 1")
        Integer maxInFlight) {
}
