package com.notifly.notification.ratelimit;

import com.notifly.notification.common.model.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket rate limiting, per (tenant, channel).
 *
 * <p>Buckets live in memory. That is the correct scope for this service: the brief puts
 * distributed systems out of scope, so there is exactly one instance, and an in-memory bucket is
 * both accurate and free of the round trip that a database-backed counter would add to every
 * send. A multi-instance deployment would need shared counters, and that is stated in the README
 * rather than half-built here.
 *
 * <p>Policies are read from the database and cached briefly, so that changing a limit takes
 * effect without a restart but does not cost a query per send.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** How long a resolved policy is reused before being looked up again. */
    private static final Duration POLICY_CACHE_TTL = Duration.ofSeconds(10);

    private final RateLimitPolicyService policyService;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, CachedPolicy> policyCache = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitPolicyService policyService) {
        this.policyService = policyService;
    }

    /**
     * Attempts to take {@code permits} from the bucket.
     *
     * <p>All-or-nothing: a batch of ten either takes ten tokens or takes none. Partially admitting
     * a batch would leave the caller with some notifications accepted and some rejected under one
     * request id, which is far harder to reason about than a clean rejection.
     */
    public Decision tryAcquire(UUID tenantId, Channel channel, int permits) {
        Optional<RateLimitPolicy> resolved = resolvePolicy(tenantId, channel);
        if (resolved.isEmpty()) {
            return Decision.permit();
        }

        RateLimitPolicy policy = resolved.get();
        if (permits > policy.getCapacity()) {
            // A batch larger than the bucket could never be admitted, however long the caller
            // waits. Saying so is more useful than a Retry-After that will never come true.
            return Decision.denyPermanently(
                    "Batch of %d exceeds the bucket capacity of %d for %s"
                            .formatted(permits, policy.getCapacity(), channel));
        }

        Bucket bucket = buckets.computeIfAbsent(key(tenantId, channel), k -> new Bucket(policy));
        return bucket.tryAcquire(policy, permits);
    }

    /** Returns unused permits, for work that was claimed but then not sent. */
    public void release(UUID tenantId, Channel channel, int permits) {
        Bucket bucket = buckets.get(key(tenantId, channel));
        if (bucket != null) {
            bucket.release(permits);
        }
    }

    /** Drops cached state for a tenant, so a policy change applies immediately. */
    public void reset(UUID tenantId) {
        buckets.keySet().removeIf(k -> k.startsWith(tenantId.toString()));
        policyCache.keySet().removeIf(k -> k.startsWith(tenantId.toString()));
    }

    /** Clears everything. Used by tests that need a known starting state. */
    public void resetAll() {
        buckets.clear();
        policyCache.clear();
    }

    private Optional<RateLimitPolicy> resolvePolicy(UUID tenantId, Channel channel) {
        String key = key(tenantId, channel);
        CachedPolicy cached = policyCache.get(key);
        if (cached != null && !cached.isStale()) {
            return Optional.ofNullable(cached.policy());
        }

        Optional<RateLimitPolicy> policy = policyService.resolve(tenantId, channel);
        policyCache.put(key, new CachedPolicy(policy.orElse(null), System.nanoTime()));
        return policy;
    }

    private String key(UUID tenantId, Channel channel) {
        return tenantId + ":" + channel;
    }

    private record CachedPolicy(RateLimitPolicy policy, long cachedAtNanos) {
        boolean isStale() {
            return System.nanoTime() - cachedAtNanos > POLICY_CACHE_TTL.toNanos();
        }
    }

    /**
     * The outcome of a rate limit check.
     *
     * @param allowed            whether the permits were granted
     * @param retryAfterSeconds  how long until enough tokens exist, 0 when allowed
     * @param reason             why it was denied, null when allowed
     * @param permanentlyDenied  true when waiting cannot help, because the request exceeds capacity
     */
    public record Decision(boolean allowed, long retryAfterSeconds, String reason, boolean permanentlyDenied) {

        // Named permit/deny rather than allowed/denied because a record's accessor already
        // owns the name allowed().
        static Decision permit() {
            return new Decision(true, 0, null, false);
        }

        static Decision deny(long retryAfterSeconds, String reason) {
            return new Decision(false, Math.max(1, retryAfterSeconds), reason, false);
        }

        static Decision denyPermanently(String reason) {
            return new Decision(false, 0, reason, true);
        }
    }

    /**
     * One token bucket.
     *
     * <p>Synchronised rather than lock-free. The critical section is a handful of arithmetic
     * operations, contention is per (tenant, channel) rather than global, and a correct
     * compare-and-swap refill loop is materially harder to get right than this is to make fast.
     */
    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos;

        Bucket(RateLimitPolicy policy) {
            // Starts full: a tenant's first send should not be throttled by an empty bucket.
            this.tokens = policy.getCapacity();
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized Decision tryAcquire(RateLimitPolicy policy, int permits) {
            refill(policy);

            if (tokens >= permits) {
                tokens -= permits;
                return Decision.permit();
            }

            double shortfall = permits - tokens;
            double perSecond = policy.ratePerSecond();
            long retryAfter = perSecond <= 0 ? 60 : (long) Math.ceil(shortfall / perSecond);

            log.debug("Rate limit hit: needed {} tokens, had {}", permits, String.format("%.2f", tokens));
            return Decision.deny(retryAfter,
                    "Rate limit exceeded; %d permits requested, %.0f available"
                            .formatted(permits, Math.floor(tokens)));
        }

        synchronized void release(int permits) {
            tokens += permits;
        }

        private void refill(RateLimitPolicy policy) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds <= 0) {
                return;
            }

            tokens = Math.min(policy.getCapacity(), tokens + elapsedSeconds * policy.ratePerSecond());
            lastRefillNanos = now;
        }
    }
}
