package com.moviebooking.theater;

import java.time.Instant;
import java.time.ZoneId;

/** Where and when a showtime takes place, for confirmations. {@code zone} is the theater's city time zone. */
public record ShowtimeDetails(long showtimeId, long movieId, long screenId, Instant startsAt, String theaterName,
        String screenName, String city, ZoneId zone) {
}
