package com.moviebooking.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.moviebooking.catalog.MovieView.LanguageView;

/**
 * Showtime detail. Local date and times are in the theater's {@code timeZone}. {@code prices}
 * lists each seat category with its price and number of seats; it says nothing about availability,
 * which only the seat map reports.
 */
public record ShowtimeView(long showtimeId, String status, Instant startsAt, Instant endsAt, LocalDate localDate,
        LocalTime localStartTime, LocalTime localEndTime, String timeZone, MovieView movie, LanguageView language,
        TheaterView theater, ScreenView screen, List<PriceView> prices) {

    public record TheaterView(long id, String name, String address, CityView city) {
    }

    public record CityView(long id, String name, String stateCode) {
    }

    public record ScreenView(long id, String name) {
    }

    public record PriceView(String category, BigDecimal price, int seatCount) {
    }
}
