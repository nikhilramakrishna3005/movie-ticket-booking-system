package com.moviebooking.theater;

import java.time.Instant;

/** Where and when a showtime takes place, for confirmations. */
public record ShowtimeDetails(long showtimeId, long movieId, long screenId, Instant startsAt, String theaterName,
        String screenName, String city) {
}
