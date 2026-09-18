package com.notifly.notification.common.config;

import com.notifly.notification.common.model.Channel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.EnumMap;
import java.util.Map;

/**
 * Dispatch engine configuration, bound from {@code notifly.dispatch.*}.
 *
 * <p>Every number that governs throughput lives here rather than being compiled in, because the
 * right values depend on the deployment and because the load test needs to change them.
 *
 * @param enabled              whether the pollers run; disabled in tests that drive dispatch by hand
 * @param pollIntervalMs       how often the dispatcher looks for claimable work
 * @param batchSize            how many notifications one claim cycle takes per channel
 * @param leaseSeconds         how long a claimed notification stays leased before it is reapable
 * @param maxInFlightPerTenant ceiling on one tenant's concurrent sends per channel
 * @param queueCapacity        bounded queue depth in front of each channel's worker pool
 * @param workers              worker thread count per channel
 * @param retry                retry and backoff policy
 * @param simulator            per-channel simulator behaviour
 */
@Validated
@ConfigurationProperties(prefix = "notifly.dispatch")
public record DispatchProperties(
        boolean enabled,

        @Min(50) @Max(60_000) int pollIntervalMs,
        @Min(1) @Max(1000) int batchSize,
        @Min(5) @Max(3600) int leaseSeconds,
        @Min(1) @Max(10_000) int maxInFlightPerTenant,
        @Min(1) @Max(100_000) int queueCapacity,

        Map<Channel, Integer> workers,
        Retry retry,
        Map<Channel, Simulator> simulator) {

    public DispatchProperties {
        workers = workers == null ? new EnumMap<>(Channel.class) : new EnumMap<>(workers);
        simulator = simulator == null ? new EnumMap<>(Channel.class) : new EnumMap<>(simulator);
        retry = retry == null ? Retry.defaults() : retry;
    }

    /** Worker count for a channel, defaulting to a small pool rather than zero. */
    public int workersFor(Channel channel) {
        return workers.getOrDefault(channel, 4);
    }

    public Simulator simulatorFor(Channel channel) {
        return simulator.getOrDefault(channel, Simulator.reliable());
    }

    /**
     * Retry and backoff policy.
     *
     * @param maxAttempts           total attempts before a notification is failed for good
     * @param initialBackoffSeconds delay before the second attempt
     * @param maxBackoffSeconds     ceiling on the delay, so exponential growth stays bounded
     * @param multiplier            growth factor between attempts
     * @param jitterFactor          proportion of the delay randomised, to break up retry storms
     */
    public record Retry(
            @Min(1) @Max(20) int maxAttempts,
            @Min(1) @Max(3600) int initialBackoffSeconds,
            @Min(1) @Max(86_400) int maxBackoffSeconds,
            double multiplier,
            double jitterFactor) {

        public static Retry defaults() {
            return new Retry(5, 2, 300, 2.0, 0.2);
        }
    }

    /**
     * Simulated provider behaviour for one channel.
     *
     * @param minLatencyMs         lower bound of simulated latency
     * @param maxLatencyMs         upper bound of simulated latency
     * @param transientFailureRate probability of a retryable failure
     * @param permanentFailureRate probability of a non-retryable failure
     */
    public record Simulator(
            @Min(0) int minLatencyMs,
            @Min(0) int maxLatencyMs,
            double transientFailureRate,
            double permanentFailureRate) {

        public static Simulator reliable() {
            return new Simulator(0, 0, 0.0, 0.0);
        }
    }
}
