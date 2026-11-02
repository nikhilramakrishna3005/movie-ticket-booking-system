package com.moviebooking.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The zone showtimes are scheduled and searched in. Every seeded theater is in Texas, and the
 * schema has no timezone column, so the application uses one zone rather than a UTC date.
 * Day boundaries follow this zone, including daylight-saving transitions.
 */
public final class TheaterZone {

    public static final ZoneId ZONE = ZoneId.of("America/Chicago");

    public static final String ID = ZONE.getId();

    private TheaterZone() {
    }

    public static LocalDate localDate(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    public static LocalTime localTime(Instant instant) {
        return LocalTime.ofInstant(instant, ZONE);
    }

    /** First instant of {@code date} in the theater zone. */
    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    /** A wall-clock time typed by an admin, interpreted in the theater zone. */
    public static Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZONE).toInstant();
    }
}
