package com.moviebooking.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import com.moviebooking.catalog.MovieView.LanguageView;

/**
 * One search hit, flat for list rendering. {@code startsAt}/{@code endsAt} are UTC instants;
 * {@code localDate}/{@code localStartTime} are the same start in the theater's {@code timeZone}.
 */
public record ShowtimeSearchResult(long showtimeId, long movieId, String movieTitle, String certification,
        int durationMinutes, LanguageView language, long theaterId, String theaterName, long cityId,
        String cityName, String stateCode, long screenId, String screenName, Instant startsAt, Instant endsAt,
        LocalDate localDate, LocalTime localStartTime, String timeZone) {
}
