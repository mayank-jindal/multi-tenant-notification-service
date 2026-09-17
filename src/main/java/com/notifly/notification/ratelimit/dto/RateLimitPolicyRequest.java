package com.notifly.notification.ratelimit.dto;

import com.notifly.notification.common.model.Channel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A token bucket definition.
 *
 * @param channel             the channel this applies to, or null for every channel
 * @param capacity            bucket depth — the largest burst allowed before throttling
 * @param refillTokens        tokens added each refill period
 * @param refillPeriodSeconds length of the refill period
 * @param enabled             whether the policy is in force
 */
@Schema(description = "A token bucket rate limit")
public record RateLimitPolicyRequest(

        @Schema(description = "Null applies the policy to every channel", example = "EMAIL")
        Channel channel,

        @Schema(description = "Burst capacity", example = "100")
        @NotNull(message = "capacity is required")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 1_000_000, message = "must be at most 1000000")
        Integer capacity,

        @Schema(description = "Tokens added per refill period", example = "50")
        @NotNull(message = "refillTokens is required")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 1_000_000, message = "must be at most 1000000")
        Integer refillTokens,

        @Schema(description = "Refill period in seconds", example = "1")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 3600, message = "must be at most 3600")
        Integer refillPeriodSeconds,

        Boolean enabled) {

    public int refillPeriodOrDefault() {
        return refillPeriodSeconds == null ? 1 : refillPeriodSeconds;
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
