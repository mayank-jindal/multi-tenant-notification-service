package com.notifly.notification.ratelimit.dto;

import com.notifly.notification.ratelimit.RateLimitPolicy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * @param id                  the policy's id
 * @param tenantId            the tenant it applies to, or null for the platform default
 * @param channel             the channel it applies to, or null for every channel
 * @param capacity            burst capacity
 * @param refillTokens        tokens added per refill period
 * @param refillPeriodSeconds refill period length
 * @param ratePerSecond       sustained rate, for comparing policies with different periods
 * @param scope               human-readable description of what this policy governs
 * @param enabled             whether the policy is in force
 */
@Schema(description = "A configured rate limit")
public record RateLimitPolicyResponse(
        UUID id,
        UUID tenantId,
        String channel,
        int capacity,
        int refillTokens,
        int refillPeriodSeconds,
        double ratePerSecond,
        String scope,
        boolean enabled) {

    public static RateLimitPolicyResponse from(RateLimitPolicy policy) {
        String scope = (policy.isGlobal() ? "platform" : "tenant")
                + " / " + (policy.getChannel() == null ? "all channels" : policy.getChannel().name());

        return new RateLimitPolicyResponse(
                policy.getId(),
                policy.getTenantId(),
                policy.getChannel() == null ? null : policy.getChannel().name(),
                policy.getCapacity(),
                policy.getRefillTokens(),
                policy.getRefillPeriodSeconds(),
                policy.ratePerSecond(),
                scope,
                policy.isEnabled());
    }
}
