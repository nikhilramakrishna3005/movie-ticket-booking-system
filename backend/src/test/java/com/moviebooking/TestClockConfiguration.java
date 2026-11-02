package com.moviebooking;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with a {@link MutableClock}; tests reset it before each test. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    /** The assessment date, before every seeded showtime starts (they begin 2026-11-03). */
    public static final Instant START = Instant.parse("2026-11-02T12:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
