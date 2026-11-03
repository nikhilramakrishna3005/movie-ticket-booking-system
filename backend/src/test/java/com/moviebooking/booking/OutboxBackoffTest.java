package com.moviebooking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class OutboxBackoffTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private final OutboxBackoff backoff = new OutboxBackoff(Duration.ofSeconds(1), Duration.ofSeconds(8));

    @Test
    void firstAttemptWaitsTheInitialDelay() {
        assertThat(backoff.nextAttempt(NOW, 1)).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void laterAttemptsDoubleUntilTheCap() {
        assertThat(backoff.nextAttempt(NOW, 2)).isEqualTo(NOW.plusSeconds(2));
        assertThat(backoff.nextAttempt(NOW, 3)).isEqualTo(NOW.plusSeconds(4));
        assertThat(backoff.nextAttempt(NOW, 4)).isEqualTo(NOW.plusSeconds(8));
        assertThat(backoff.nextAttempt(NOW, 12)).isEqualTo(NOW.plusSeconds(8));
    }
}
