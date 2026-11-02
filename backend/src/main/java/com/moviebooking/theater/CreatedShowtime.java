package com.moviebooking.theater;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record CreatedShowtime(long showtimeId, long theaterId, long screenId, long movieId, String languageCode,
        ShowtimeStatus status, Instant startsAt, Instant endsAt, LocalDate localDate, LocalTime localStartTime,
        String timeZone, int seatCount) {
}
