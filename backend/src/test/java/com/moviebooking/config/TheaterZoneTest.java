package com.moviebooking.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** America/Chicago day boundaries, including both standard time and daylight-saving time. */
class TheaterZoneTest {

    @Test
    void novemberDateUsesCentralStandardTime() {
        Instant at0430Utc = Instant.parse("2026-11-03T04:30:00Z");

        assertThat(TheaterZone.localDate(at0430Utc)).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(TheaterZone.localTime(at0430Utc)).isEqualTo(LocalTime.parse("22:30"));
        assertThat(TheaterZone.localDate(Instant.parse("2026-11-03T06:00:00Z")))
                .isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(TheaterZone.startOfDay(LocalDate.parse("2026-11-03")))
                .isEqualTo(Instant.parse("2026-11-03T06:00:00Z"));
        assertThat(TheaterZone.startOfDay(LocalDate.parse("2026-11-04")))
                .isEqualTo(Instant.parse("2026-11-04T06:00:00Z"));
        assertThat(TheaterZone.toInstant(LocalDateTime.parse("2026-11-03T00:30:00")))
                .isEqualTo(Instant.parse("2026-11-03T06:30:00Z"));
        assertThat(TheaterZone.toInstant(LocalDateTime.parse("2026-11-03T23:30:00")))
                .isEqualTo(Instant.parse("2026-11-04T05:30:00Z"));
    }

    @Test
    void julyDateUsesCentralDaylightTime() {
        assertThat(TheaterZone.startOfDay(LocalDate.parse("2026-07-15")))
                .isEqualTo(Instant.parse("2026-07-15T05:00:00Z"));
        assertThat(TheaterZone.toInstant(LocalDateTime.parse("2026-07-15T00:30:00")))
                .isEqualTo(Instant.parse("2026-07-15T05:30:00Z"));
        assertThat(TheaterZone.localDate(Instant.parse("2026-07-15T04:30:00Z")))
                .isEqualTo(LocalDate.parse("2026-07-14"));
    }
}
