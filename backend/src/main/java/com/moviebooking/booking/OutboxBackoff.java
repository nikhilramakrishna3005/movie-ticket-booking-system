package com.moviebooking.booking;

import java.time.Duration;
import java.time.Instant;

/**
 * Bounded exponential backoff for unpublished outbox rows. Attempt 1 waits {@code initial};
 * each later attempt doubles, capped at {@code max}.
 */
final class OutboxBackoff {

    private final Duration initial;
    private final Duration max;

    OutboxBackoff(Duration initial, Duration max) {
        this.initial = initial;
        this.max = max;
    }

    Instant nextAttempt(Instant now, int attemptCount) {
        int shift = Math.min(Math.max(attemptCount - 1, 0), 20);
        long millis = initial.toMillis() << shift;
        if (millis <= 0 || millis > max.toMillis()) {
            millis = max.toMillis();
        }
        return now.plusMillis(millis);
    }
}
