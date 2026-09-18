package com.notifly.notification.dispatch;

import com.notifly.notification.common.config.DispatchProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Computes when a failed notification should next be attempted.
 *
 * <p>Exponential growth with a ceiling, plus jitter. The jitter is the part that is easy to omit
 * and expensive to omit: when a provider recovers from an outage, every notification that failed
 * during it has the same retry schedule, so without randomisation they all return at the same
 * instant and knock the provider over again. Spreading them breaks that synchronisation.
 */
@Component
public class BackoffCalculator {

    private final DispatchProperties.Retry retry;

    public BackoffCalculator(DispatchProperties dispatchProperties) {
        this.retry = dispatchProperties.retry();
    }

    /**
     * Delay before the given attempt number.
     *
     * @param attemptNumber the attempt that just failed, starting at 1
     */
    public Duration delayForAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            attemptNumber = 1;
        }

        // Computed in double to avoid overflow: at a multiplier of 2 and twenty attempts, a long
        // in seconds would still be fine, but the ceiling makes the exact value irrelevant and
        // the overflow risk is not worth carrying.
        double seconds = retry.initialBackoffSeconds() * Math.pow(retry.multiplier(), attemptNumber - 1);
        double capped = Math.min(seconds, retry.maxBackoffSeconds());

        double jitter = capped * retry.jitterFactor();
        double lowest = Math.max(0.0, capped - jitter);
        double highest = capped + jitter;

        double chosen = lowest >= highest ? capped : ThreadLocalRandom.current().nextDouble(lowest, highest);
        return Duration.ofMillis(Math.round(chosen * 1000));
    }

    /** When the given attempt should next be tried, measured from now. */
    public Instant nextAttemptAt(int attemptNumber) {
        return Instant.now().plus(delayForAttempt(attemptNumber));
    }

    /** Whether a further attempt is permitted. */
    public boolean hasAttemptsRemaining(int attemptsSoFar, int maxAttempts) {
        return attemptsSoFar < maxAttempts;
    }

    public int defaultMaxAttempts() {
        return retry.maxAttempts();
    }
}
