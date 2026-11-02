package com.moviebooking.theater;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.catalog.CatalogService;
import com.moviebooking.catalog.Movie;
import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;
import com.moviebooking.config.TheaterZone;
import com.moviebooking.security.AccessPolicy;
import com.moviebooking.security.AuthenticatedUser;

/**
 * Creates a scheduled showtime and its AVAILABLE seat inventory. The screen's exclusion
 * constraint rejects a range that overlaps another scheduled showing; this transaction then
 * rolls back, so a rejected request leaves no showtime and no seats.
 */
@Service
public class ShowtimeScheduler {

    /** Cleaning time after the movie, matching the seeded showtimes. */
    static final Duration TURNOVER = Duration.ofMinutes(15);

    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    private static final String EXCLUSION_VIOLATION = "23P01";

    private final ScreenRepository screens;
    private final ShowtimeRepository showtimes;
    private final CatalogService catalog;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ShowtimeScheduler(ScreenRepository screens, ShowtimeRepository showtimes, CatalogService catalog,
            JdbcTemplate jdbc, Clock clock) {
        this.screens = screens;
        this.showtimes = showtimes;
        this.catalog = catalog;
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
    }

    @Transactional
    public ScheduledShowtime create(AuthenticatedUser admin, Long screenId, Long movieId, String languageCode,
            LocalDateTime startsAtLocal, BigDecimal standardPrice, BigDecimal premiumPrice) {
        if (screenId == null || movieId == null || startsAtLocal == null) {
            throw ApiException.badRequest("INVALID_REQUEST",
                    "screenId, movieId and startsAt are required", null);
        }
        if (languageCode == null || !languageCode.matches("[a-z]{2}")) {
            throw ApiException.badRequest("INVALID_REQUEST", "languageCode must be a two-letter code", null);
        }
        requirePrice(standardPrice, "standardPrice");
        requirePrice(premiumPrice, "premiumPrice");

        Screen screen = screens.findById(screenId).orElseThrow(ResourceNotFoundException::new);
        AccessPolicy.requireAssignedTheater(admin, screen.getTheaterId());
        Movie movie = catalog.getMovie(movieId);
        catalog.requireLanguage(languageCode);

        Instant startsAt = TheaterZone.toInstant(startsAtLocal);
        Instant endsAt = startsAt.plus(Duration.ofMinutes(movie.getDurationMinutes())).plus(TURNOVER);
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Showtime showtime = insert(Showtime.schedule(screenId, movieId, languageCode, startsAt, endsAt, createdAt));
        int seatCount = seedSeats(showtime.getId(), screenId, standardPrice, premiumPrice);
        if (seatCount == 0) {
            throw ApiException.badRequest("INVALID_REQUEST", "The screen has no seats", null);
        }
        return new ScheduledShowtime(showtime.getId(), screen.getTheaterId(), screenId, movieId, languageCode,
                startsAt, endsAt, TheaterZone.localDate(startsAt), TheaterZone.localTime(startsAt), TheaterZone.ID,
                seatCount, standardPrice, premiumPrice);
    }

    private Showtime insert(Showtime showtime) {
        try {
            return showtimes.saveAndFlush(showtime);
        } catch (DataIntegrityViolationException ex) {
            if (isOverlap(ex)) {
                throw ApiException.conflict("SHOWTIME_OVERLAP",
                        "This screen already has a scheduled showtime in that time range", null);
            }
            throw ex;
        }
    }

    private int seedSeats(long showtimeId, long screenId, BigDecimal standardPrice, BigDecimal premiumPrice) {
        return jdbc.update("""
                INSERT INTO show_seat (showtime_id, seat_id, screen_id, status, price)
                SELECT :showtimeId, se.id, se.screen_id, 'AVAILABLE',
                       CASE se.category WHEN 'PREMIUM' THEN :premium ELSE :standard END
                FROM seat se
                WHERE se.screen_id = :screenId""",
                new MapSqlParameterSource()
                        .addValue("showtimeId", showtimeId)
                        .addValue("screenId", screenId)
                        .addValue("standard", standardPrice)
                        .addValue("premium", premiumPrice));
    }

    private static void requirePrice(BigDecimal price, String name) {
        if (price == null || price.signum() <= 0 || price.scale() > 2 || price.compareTo(MAX_PRICE) > 0) {
            throw ApiException.badRequest("INVALID_REQUEST",
                    name + " must be greater than 0 with at most 2 decimal places", null);
        }
    }

    private static boolean isOverlap(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                    && violation.getConstraintName().contains("showtime_no_overlap")) {
                return true;
            }
            if (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    public record ScheduledShowtime(long showtimeId, long theaterId, long screenId, long movieId, String languageCode,
            Instant startsAt, Instant endsAt, LocalDate localDate, LocalTime localTime,
            String timeZone, int seatCount, BigDecimal standardPrice, BigDecimal premiumPrice) {
    }
}
