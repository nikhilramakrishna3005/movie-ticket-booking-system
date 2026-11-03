package com.moviebooking.theater;

import java.util.List;

/** A screen's seating plan, seats in row then seat-number order. */
public record ScreenDetail(long screenId, long theaterId, String name, boolean layoutLocked, List<SeatView> seats) {

    public record SeatView(long seatId, String rowLabel, int seatNumber, SeatCategory category) {
    }
}
