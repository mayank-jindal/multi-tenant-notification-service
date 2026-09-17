package com.notifly.notification.ratelimit;

import com.notifly.notification.common.model.BaseEntity;
import com.notifly.notification.common.model.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A token bucket definition.
 *
 * <p>Not a {@code TenantOwnedEntity}: a policy with a null tenant is the platform-wide default,
 * which is the whole point of the type. Resolution for a given (tenant, channel) walks from most
 * to least specific — tenant+channel, tenant+any, global+channel, global+any — so a platform
 * admin can set a floor for everyone and still carve out exceptions.
 */
@Entity
@Table(name = "rate_limit_policies")
public class RateLimitPolicy extends BaseEntity {

    /** Null means this is the platform-wide policy. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Null means the policy applies to every channel. */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 16)
    private Channel channel;

    /** Bucket depth: the largest burst tolerated before throttling starts. */
    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "refill_tokens", nullable = false)
    private int refillTokens;

    @Column(name = "refill_period_seconds", nullable = false)
    private int refillPeriodSeconds = 1;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    protected RateLimitPolicy() {
        // for JPA
    }

    public RateLimitPolicy(UUID tenantId, Channel channel, int capacity, int refillTokens, int refillPeriodSeconds) {
        this.tenantId = tenantId;
        this.channel = channel;
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriodSeconds = refillPeriodSeconds;
    }

    /** Sustained rate in tokens per second, for comparing policies of different periods. */
    public double ratePerSecond() {
        return (double) refillTokens / refillPeriodSeconds;
    }

    /** How specific this policy is; higher wins during resolution. */
    public int specificity() {
        int score = 0;
        if (tenantId != null) {
            score += 2;
        }
        if (channel != null) {
            score += 1;
        }
        return score;
    }

    public boolean isGlobal() {
        return tenantId == null;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Channel getChannel() {
        return channel;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getRefillTokens() {
        return refillTokens;
    }

    public void setRefillTokens(int refillTokens) {
        this.refillTokens = refillTokens;
    }

    public int getRefillPeriodSeconds() {
        return refillPeriodSeconds;
    }

    public void setRefillPeriodSeconds(int refillPeriodSeconds) {
        this.refillPeriodSeconds = refillPeriodSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
