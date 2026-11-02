package com.moviebooking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** UTC test clock that only moves when a test sets or advances it. */
public final class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void set(Instant newInstant) {
        instant = newInstant;
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The application clock is always UTC");
    }
}
