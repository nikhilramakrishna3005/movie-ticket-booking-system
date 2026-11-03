package com.moviebooking.theater;

import java.util.List;

/** A theater with its screens, as shown to the admins assigned to it. */
public record TheaterOverview(long theaterId, String name, String address, CityView city,
        List<ScreenSummary> screens) {

    /** {@code layoutLocked} once any showtime exists, because its inventory references the seats. */
    public record ScreenSummary(long screenId, String name, int rowCount, int seatCount, int standardSeats,
            int premiumSeats, boolean layoutLocked) {
    }
}
