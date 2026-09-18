package com.notifly.notification.provider;

import com.notifly.notification.common.model.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A provider simulator with configurable latency and failure behaviour.
 *
 * <p>One instance is registered per channel. Its purpose is to make the delivery pipeline's
 * interesting behaviour — backoff, retry exhaustion, permanent-failure short-circuiting,
 * backpressure — reproducible on demand rather than dependent on a third party having a bad day.
 *
 * <p>It also performs the address validation a real provider would, so that an obviously invalid
 * recipient produces a permanent failure rather than being retried five times at full cost.
 */
public class SimulatedChannelProvider implements ChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedChannelProvider.class);

    public static final String PROVIDER_CODE = "SIMULATOR";

    private final Channel channel;
    private final SimulatorSettings settings;

    public SimulatedChannelProvider(Channel channel, SimulatorSettings settings) {
        this.channel = channel;
        this.settings = settings;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public ProviderResult send(OutboundMessage message) {
        // Checked before anything else: an address a provider would reject outright should never
        // consume a retry budget.
        String rejection = validateRecipient(message.recipient());
        if (rejection != null) {
            return ProviderResult.permanentFailure("INVALID_ADDRESS", rejection);
        }

        simulateLatency();

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double roll = random.nextDouble();

        if (roll < settings.permanentFailureRate()) {
            return ProviderResult.permanentFailure("PROVIDER_REJECTED",
                    "Simulated permanent rejection by the " + channel + " provider");
        }
        if (roll < settings.permanentFailureRate() + settings.transientFailureRate()) {
            return ProviderResult.transientFailure("PROVIDER_UNAVAILABLE",
                    "Simulated transient failure by the " + channel + " provider");
        }

        String providerMessageId = channel.name().toLowerCase() + "-" + UUID.randomUUID();
        log.debug("Simulated {} delivery to {} (attempt {}) -> {}",
                channel, message.recipient(), message.attemptNumber(), providerMessageId);

        return ProviderResult.success(providerMessageId);
    }

    /**
     * Rejects addresses no real provider would accept.
     *
     * <p>Deliberately shallow — full email or phone number validation is a famously bad idea, and
     * a real provider is the only authority on what it will accept. This catches the obviously
     * malformed and nothing more.
     */
    private String validateRecipient(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "Recipient address is empty";
        }
        return switch (channel) {
            case EMAIL -> recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
                    ? null : "Not a valid email address";
            case SMS -> recipient.matches("^\\+?[0-9][0-9\\-\\s()]{5,19}$")
                    ? null : "Not a valid phone number";
            case PUSH -> recipient.length() >= 8
                    ? null : "Device token is too short to be valid";
            // In-app messages are written to our own store, so any non-blank recipient
            // reference is addressable.
            case IN_APP -> null;
        };
    }

    private void simulateLatency() {
        int min = settings.minLatencyMs();
        int max = Math.max(min, settings.maxLatencyMs());
        if (max <= 0) {
            return;
        }
        try {
            Thread.sleep(min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1));
        } catch (InterruptedException ex) {
            // Restoring the flag lets the pool's shutdown actually stop this worker; swallowing
            // it would make the executor hang on termination.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Per-channel simulator behaviour.
     *
     * @param minLatencyMs          lower bound of simulated provider latency
     * @param maxLatencyMs          upper bound of simulated provider latency
     * @param transientFailureRate  probability of a retryable failure, 0.0 to 1.0
     * @param permanentFailureRate  probability of a non-retryable failure, 0.0 to 1.0
     */
    public record SimulatorSettings(
            int minLatencyMs,
            int maxLatencyMs,
            double transientFailureRate,
            double permanentFailureRate) {

        public SimulatorSettings {
            if (transientFailureRate < 0 || permanentFailureRate < 0
                || transientFailureRate + permanentFailureRate > 1.0) {
                throw new IllegalArgumentException(
                        "Simulator failure rates must be non-negative and sum to at most 1.0");
            }
        }

        /** Fast and reliable: the default, so nothing fails unless a test asks it to. */
        public static SimulatorSettings reliable() {
            return new SimulatorSettings(0, 0, 0.0, 0.0);
        }
    }
}
