package com.moviebooking.theater;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Every showtime of a theater that starts on {@code date} in its city's time zone, cancelled and
 * already started ones included. A price is null if the screen has no seats of that category.
 */
public record TheaterSchedule(long theaterId, LocalDate date, String timeZone, List<Entry> showtimes) {

    public record Entry(long showtimeId, long screenId, String screenName, long movieId, String movieTitle,
            String languageCode, ShowtimeStatus status, Instant startsAt, Instant endsAt, LocalTime localStartTime,
            LocalTime localEndTime, int seatCount, int bookedSeats, int heldSeats, BigDecimal standardPrice,
            BigDecimal premiumPrice) {
    }
}
