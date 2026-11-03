package com.moviebooking;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Looks up seeded venue data by name and inserts or removes showtimes that are not part of the
 * V7 seed. Seeded showtimes are recognized by their fixed {@code created_at}.
 */
public final class ShowtimeFixtures {

    private static final OffsetDateTime SEED_CREATED_AT = utc(Instant.parse("2026-11-02T00:00:00Z"));

    private final JdbcTemplate jdbc;

    public ShowtimeFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Removes every non-seed showtime with its inventory and holds; deletes follow the foreign keys. */
    public void deleteNonSeedShowtimes() {
        String nonSeed = "SELECT id FROM showtime WHERE created_at <> ?";
        jdbc.update("DELETE FROM show_seat WHERE showtime_id IN (" + nonSeed + ")", SEED_CREATED_AT);
        jdbc.update("DELETE FROM seat_hold WHERE showtime_id IN (" + nonSeed + ")", SEED_CREATED_AT);
        jdbc.update("DELETE FROM showtime WHERE created_at <> ?", SEED_CREATED_AT);
    }

    /**
     * Removes screens added by tests, with their seats. Seeded screens are the ones with seeded
     * showtimes, so this must run after {@link #deleteNonSeedShowtimes()}.
     */
    public void deleteNonSeedScreens() {
        String nonSeed = "SELECT id FROM screen WHERE id NOT IN (SELECT screen_id FROM showtime WHERE created_at = ?)";
        jdbc.update("DELETE FROM seat WHERE screen_id IN (" + nonSeed + ")", SEED_CREATED_AT);
        jdbc.update("DELETE FROM screen WHERE id IN (" + nonSeed + ")", SEED_CREATED_AT);
    }

    /** Inserts a scheduled showtime of the movie's length plus 15 minutes, with AVAILABLE seats. */
    public long insertShowtime(String theater, String screen, String movieTitle, Instant startsAt) {
        long showtimeId = jdbc.queryForObject("""
                INSERT INTO showtime (screen_id, movie_id, language_code, starts_at, ends_at, status, created_at)
                SELECT ?, m.id, m.original_language_code, ?, ? + make_interval(mins => m.duration_minutes + 15),
                       'SCHEDULED', ?
                FROM movie m WHERE m.title = ?
                RETURNING id""", Long.class, screenId(theater, screen), utc(startsAt), utc(startsAt),
                utc(TestClockConfiguration.START), movieTitle);
        jdbc.update("""
                INSERT INTO show_seat (showtime_id, seat_id, screen_id, status, price)
                SELECT st.id, se.id, se.screen_id, 'AVAILABLE', 10.00
                FROM showtime st JOIN seat se ON se.screen_id = st.screen_id
                WHERE st.id = ?""", showtimeId);
        return showtimeId;
    }

    public long screenId(String theater, String screen) {
        return jdbc.queryForObject("""
                SELECT s.id FROM screen s JOIN theater t ON t.id = s.theater_id
                WHERE t.name = ? AND s.name = ?""", Long.class, theater, screen);
    }

    public long movieId(String title) {
        return jdbc.queryForObject("SELECT id FROM movie WHERE title = ?", Long.class, title);
    }

    public long cityId(String name) {
        return jdbc.queryForObject("SELECT id FROM city WHERE name = ?", Long.class, name);
    }

    public long theaterId(String name) {
        return jdbc.queryForObject("SELECT id FROM theater WHERE name = ?", Long.class, name);
    }

    public long nonSeedShowtimeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM showtime WHERE created_at <> ?", Long.class,
                SEED_CREATED_AT);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
