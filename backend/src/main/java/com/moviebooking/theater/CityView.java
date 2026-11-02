package com.moviebooking.theater;

/** {@code timeZone} is the IANA zone in which the city's showtimes and search dates are expressed. */
public record CityView(long id, String name, String stateCode, String timeZone) {
}
