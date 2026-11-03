package com.moviebooking.theater;

/** Result of cancelling a showtime, or of repeating cancel on one already cancelled. */
public record CancelledShowtime(long showtimeId, long theaterId, long screenId, ShowtimeStatus status) {
}
