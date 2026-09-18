package com.notifly.notification.dispatch;

import com.notifly.notification.common.config.DispatchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for retry backoff. */
class BackoffCalculatorTest {

    @Test
    @DisplayName("delay grows exponentially with the attempt number")
    void delayGrowsExponentially() {
        BackoffCalculator calculator = calculatorWith(2, 300, 2.0, 0.0);

        assertThat(calculator.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(calculator.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(calculator.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(calculator.delayForAttempt(4)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    @DisplayName("growth is capped, so a late attempt does not wait for days")
    void delayIsCapped() {
        BackoffCalculator calculator = calculatorWith(2, 60, 2.0, 0.0);

        // Without a ceiling, attempt 20 at multiplier 2 would be over a decade away.
        assertThat(calculator.delayForAttempt(20)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("jitter spreads retries instead of synchronising them")
    void jitterProducesVariation() {
        // The reason jitter matters: when a provider recovers from an outage, every notification
        // that failed during it has the same schedule. Without randomisation they all return at
        // the same instant and knock it over again.
        BackoffCalculator calculator = calculatorWith(10, 300, 2.0, 0.5);

        Set<Long> observed = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            observed.add(calculator.delayForAttempt(3).toMillis());
        }

        assertThat(observed).hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("jitter stays within its configured band")
    void jitterIsBounded() {
        BackoffCalculator calculator = calculatorWith(10, 300, 2.0, 0.2);

        for (int i = 0; i < 200; i++) {
            long seconds = calculator.delayForAttempt(1).toSeconds();
            assertThat(seconds).isBetween(7L, 13L);
        }
    }

    @Test
    @DisplayName("an attempt number below one is treated as the first")
    void invalidAttemptNumbersAreClamped() {
        BackoffCalculator calculator = calculatorWith(5, 300, 2.0, 0.0);

        assertThat(calculator.delayForAttempt(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(calculator.delayForAttempt(-3)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("the retry budget is enforced")
    void budgetIsEnforced() {
        BackoffCalculator calculator = calculatorWith(2, 300, 2.0, 0.0);

        assertThat(calculator.hasAttemptsRemaining(4, 5)).isTrue();
        assertThat(calculator.hasAttemptsRemaining(5, 5)).isFalse();
        assertThat(calculator.hasAttemptsRemaining(6, 5)).isFalse();
    }

    private BackoffCalculator calculatorWith(int initial, int max, double multiplier, double jitter) {
        return new BackoffCalculator(new DispatchProperties(
                true, 500, 50, 60, 20, 500,
                Map.of(),
                new DispatchProperties.Retry(5, initial, max, multiplier, jitter),
                Map.of()));
    }
}
