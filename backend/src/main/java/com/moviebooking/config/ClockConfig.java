package com.moviebooking.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of time for all expiry logic (docs/architecture.md §7). Code must use this bean,
 * never {@code Instant.now()} or SQL {@code NOW()}, so tests can substitute a controllable clock.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
