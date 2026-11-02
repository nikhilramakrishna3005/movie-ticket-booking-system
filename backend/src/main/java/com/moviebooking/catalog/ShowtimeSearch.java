package com.moviebooking.catalog;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.catalog.CatalogService.LanguageInfo;
import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;
import com.moviebooking.config.TheaterZone;

/**
 * Showtime discovery (docs/architecture.md §11). One read-only query across catalog and theater
 * tables; it changes nothing. A date is a calendar day in {@link TheaterZone#ZONE}, not a UTC day.
 *
 * <p>Name matching uses {@code word_similarity}, so a typo is scored against the best matching
 * stretch of the title ("Starlght" against "Starlight Cinemas Downtown") rather than the whole
 * string. The cutoff is 0.5: it keeps the seeded one-character typos and drops near misses such
 * as "Starlght" against "Lone Star". Very short names have too few trigrams for a one-character
 * typo to clear that cutoff.
 */
@Service
@Transactional(readOnly = true)
public class ShowtimeSearch {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_QUERY_LENGTH = 80;
    static final double WORD_SIMILARITY_THRESHOLD = 0.5;

    private static final String GENRES = """
            (SELECT string_agg(g.name, chr(31) ORDER BY g.name)
             FROM movie_genre mg
             JOIN genre g ON g.id = mg.genre_id
             WHERE mg.movie_id = m.id)""";

    private static final String FROM = """
            FROM showtime st
            JOIN movie m ON m.id = st.movie_id
            JOIN screen sc ON sc.id = st.screen_id
            JOIN theater t ON t.id = sc.theater_id
            JOIN city c ON c.id = t.city_id
            JOIN language spoken ON spoken.code = st.language_code
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ShowtimeSearch(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
    }

    public ShowtimePage search(Long cityId, LocalDate date, String query, String language, String genre,
            Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : page;
        if (resolvedPage < 0) {
            throw ApiException.badRequest("INVALID_REQUEST", "page must be zero or greater", null);
        }
        if (size != null && size < 1) {
            throw ApiException.badRequest("INVALID_REQUEST", "size must be at least 1", null);
        }
        int resolvedSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String resolvedQuery = blankToNull(query);
        if (resolvedQuery != null && resolvedQuery.length() > MAX_QUERY_LENGTH) {
            throw ApiException.badRequest("INVALID_REQUEST",
                    "q must be at most " + MAX_QUERY_LENGTH + " characters", null);
        }
        String resolvedLanguage = blankToNull(language);
        String resolvedGenre = blankToNull(genre);
        if (resolvedLanguage != null && !exists("SELECT EXISTS(SELECT 1 FROM language WHERE code = :value)",
                resolvedLanguage)) {
            throw ApiException.badRequest("INVALID_REQUEST", "Unknown language code", null);
        }
        if (resolvedGenre != null && !exists(
                "SELECT EXISTS(SELECT 1 FROM genre WHERE lower(name) = lower(:value))", resolvedGenre)) {
            throw ApiException.badRequest("INVALID_REQUEST", "Unknown genre", null);
        }
        if (cityId != null && !exists("SELECT EXISTS(SELECT 1 FROM city WHERE id = :value)", cityId)) {
            throw new ResourceNotFoundException();
        }
        LocalDate resolvedDate = date == null ? TheaterZone.localDate(clock.instant()) : date;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("dayStart", Timestamp.from(TheaterZone.startOfDay(resolvedDate)))
                .addValue("dayEnd", Timestamp.from(TheaterZone.startOfDay(resolvedDate.plusDays(1))))
                .addValue("cityId", cityId)
                .addValue("language", resolvedLanguage)
                .addValue("genre", resolvedGenre)
                .addValue("q", resolvedQuery)
                .addValue("threshold", WORD_SIMILARITY_THRESHOLD);
        String where = where(cityId, resolvedLanguage, resolvedGenre, resolvedQuery);
        long total = jdbc.queryForObject("SELECT count(*) " + FROM + where, params, Long.class);
        params.addValue("limit", resolvedSize).addValue("offset", (long) resolvedPage * resolvedSize);
        String order = resolvedQuery == null
                ? "st.starts_at, st.id"
                : "greatest(word_similarity(:q, m.title), word_similarity(:q, t.name)) DESC, st.starts_at, st.id";
        List<ShowtimeCard> results = jdbc.query("""
                SELECT st.id AS showtime_id, st.starts_at,
                       m.id AS movie_id, m.title, m.certification, m.duration_minutes, %s AS genres,
                       t.id AS theater_id, t.name AS theater_name, c.id AS city_id, c.name AS city_name,
                       c.state_code, sc.name AS screen_name, spoken.code AS language_code, spoken.name AS language_name
                %s%s
                ORDER BY %s
                LIMIT :limit OFFSET :offset""".formatted(GENRES, FROM, where, order), params, (rs, row) -> card(rs));
        int totalPages = total == 0 ? 0 : (int) ((total + resolvedSize - 1) / resolvedSize);
        return new ShowtimePage(resolvedDate, TheaterZone.ID, resolvedPage, resolvedSize, total, totalPages, results);
    }

    public ShowtimeView get(long showtimeId) {
        List<ShowtimeView> found = jdbc.query("""
                SELECT st.id AS showtime_id, st.status, st.starts_at, st.ends_at,
                       m.id AS movie_id, m.title, m.certification, m.duration_minutes, m.release_date,
                       %s AS genres, original.code AS original_code, original.name AS original_name,
                       t.id AS theater_id, t.name AS theater_name, t.address, c.id AS city_id,
                       c.name AS city_name, c.state_code, sc.id AS screen_id, sc.name AS screen_name,
                       spoken.code AS language_code, spoken.name AS language_name
                FROM showtime st
                JOIN movie m ON m.id = st.movie_id
                JOIN language original ON original.code = m.original_language_code
                JOIN language spoken ON spoken.code = st.language_code
                JOIN screen sc ON sc.id = st.screen_id
                JOIN theater t ON t.id = sc.theater_id
                JOIN city c ON c.id = t.city_id
                WHERE st.id = :id""".formatted(GENRES),
                new MapSqlParameterSource("id", showtimeId), (rs, row) -> viewWithoutSeats(rs));
        if (found.isEmpty()) {
            throw new ResourceNotFoundException();
        }
        List<SeatPrice> seats = jdbc.query("""
                SELECT se.category, ss.price,
                       count(*) AS total,
                       count(*) FILTER (WHERE ss.status = 'AVAILABLE'
                           OR (ss.status = 'HELD' AND ss.hold_expires_at <= :now)) AS available
                FROM show_seat ss
                JOIN seat se ON se.id = ss.seat_id
                WHERE ss.showtime_id = :id
                GROUP BY se.category, ss.price
                ORDER BY se.category, ss.price""",
                new MapSqlParameterSource().addValue("id", showtimeId)
                        .addValue("now", Timestamp.from(clock.instant())),
                (rs, row) -> new SeatPrice(rs.getString("category"), rs.getBigDecimal("price"),
                        rs.getInt("total"), rs.getInt("available")));
        return found.getFirst().withSeats(seats);
    }

    private static String where(Long cityId, String language, String genre, String query) {
        List<String> clauses = new ArrayList<>();
        clauses.add("st.status = 'SCHEDULED'");
        clauses.add("st.starts_at >= :dayStart AND st.starts_at < :dayEnd");
        if (cityId != null) {
            clauses.add("c.id = :cityId");
        }
        if (language != null) {
            clauses.add("st.language_code = :language");
        }
        if (genre != null) {
            clauses.add("""
                    EXISTS (SELECT 1 FROM movie_genre mg
                            JOIN genre g ON g.id = mg.genre_id
                            WHERE mg.movie_id = m.id AND lower(g.name) = lower(:genre))""");
        }
        if (query != null) {
            clauses.add("""
                    (word_similarity(:q, m.title) >= :threshold
                        OR word_similarity(:q, t.name) >= :threshold)""");
        }
        return " WHERE " + String.join(" AND ", clauses);
    }

    private boolean exists(String sql, Object value) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, new MapSqlParameterSource("value", value), Boolean.class));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static ShowtimeCard card(ResultSet rs) throws SQLException {
        Instant startsAt = rs.getTimestamp("starts_at").toInstant();
        return new ShowtimeCard(rs.getLong("showtime_id"), startsAt, TheaterZone.localDate(startsAt),
                TheaterZone.localTime(startsAt), TheaterZone.ID,
                new MovieSummary(rs.getLong("movie_id"), rs.getString("title"), rs.getString("certification"),
                        rs.getInt("duration_minutes"), genres(rs.getString("genres"))),
                new TheaterSummary(rs.getLong("theater_id"), rs.getString("theater_name"), rs.getLong("city_id"),
                        rs.getString("city_name"), rs.getString("state_code")),
                rs.getString("screen_name"),
                new LanguageInfo(rs.getString("language_code"), rs.getString("language_name")));
    }

    private static ShowtimeView viewWithoutSeats(ResultSet rs) throws SQLException {
        Instant startsAt = rs.getTimestamp("starts_at").toInstant();
        Instant endsAt = rs.getTimestamp("ends_at").toInstant();
        return new ShowtimeView(rs.getLong("showtime_id"), rs.getString("status"), startsAt, endsAt,
                TheaterZone.localDate(startsAt), TheaterZone.localTime(startsAt), TheaterZone.ID,
                new MovieDetails(rs.getLong("movie_id"), rs.getString("title"), rs.getString("certification"),
                        rs.getInt("duration_minutes"), rs.getObject("release_date", LocalDate.class),
                        new LanguageInfo(rs.getString("original_code"), rs.getString("original_name")),
                        genres(rs.getString("genres"))),
                new TheaterDetails(rs.getLong("theater_id"), rs.getString("theater_name"), rs.getString("address"),
                        rs.getLong("city_id"), rs.getString("city_name"), rs.getString("state_code")),
                new ScreenSummary(rs.getLong("screen_id"), rs.getString("screen_name")),
                new LanguageInfo(rs.getString("language_code"), rs.getString("language_name")), List.of());
    }

    private static List<String> genres(String packed) {
        if (packed == null || packed.isEmpty()) {
            return List.of();
        }
        return List.of(packed.split("\u001f", -1));
    }

    public record ShowtimePage(LocalDate date, String timeZone, int page, int size, long totalElements,
            int totalPages, List<ShowtimeCard> results) {
    }

    public record ShowtimeCard(long showtimeId, Instant startsAt, LocalDate localDate, LocalTime localTime,
            String timeZone, MovieSummary movie, TheaterSummary theater, String screenName, LanguageInfo language) {
    }

    public record MovieSummary(long id, String title, String certification, int durationMinutes, List<String> genres) {
    }

    public record TheaterSummary(long id, String name, long cityId, String city, String stateCode) {
    }

    public record ShowtimeView(long showtimeId, String status, Instant startsAt, Instant endsAt, LocalDate localDate,
            LocalTime localTime, String timeZone, MovieDetails movie, TheaterDetails theater, ScreenSummary screen,
            LanguageInfo language, List<SeatPrice> seats) {

        ShowtimeView withSeats(List<SeatPrice> seatPrices) {
            return new ShowtimeView(showtimeId, status, startsAt, endsAt, localDate, localTime, timeZone, movie,
                    theater, screen, language, seatPrices);
        }
    }

    public record MovieDetails(long id, String title, String certification, int durationMinutes, LocalDate releaseDate,
            LanguageInfo originalLanguage, List<String> genres) {
    }

    public record TheaterDetails(long id, String name, String address, long cityId, String city, String stateCode) {
    }

    public record ScreenSummary(long id, String name) {
    }

    /** One price point of one seat category, with how many of those seats are free right now. */
    public record SeatPrice(String category, BigDecimal price, int total, int available) {
    }
}
