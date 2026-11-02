package com.moviebooking.catalog;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.moviebooking.catalog.MovieView.LanguageView;
import com.moviebooking.catalog.ShowtimeView.CityView;
import com.moviebooking.catalog.ShowtimeView.PriceView;
import com.moviebooking.catalog.ShowtimeView.ScreenView;
import com.moviebooking.catalog.ShowtimeView.TheaterView;

/**
 * Read-only discovery queries. They join theater, city and seat-inventory tables owned by other
 * packages: the one documented cross-package read (docs/architecture.md §3). Plain SQL keeps the
 * pg_trgm operators and the time-zone arithmetic visible.
 */
@Repository
public class CatalogQueryRepository {

    private static final String MOVIE_SELECT = """
            SELECT m.id, m.title, m.duration_minutes, m.certification, m.release_date,
                   l.code AS language_code, l.name AS language_name,
                   array_agg(g.name ORDER BY g.name) FILTER (WHERE g.name IS NOT NULL) AS genres
            FROM movie m
            JOIN language l ON l.code = m.original_language_code
            LEFT JOIN movie_genre mg ON mg.movie_id = m.id
            LEFT JOIN genre g ON g.id = mg.genre_id
            """;

    private static final String MOVIE_GROUP = " GROUP BY m.id, l.code";

    private static final String SHOWTIME_FROM = """
            FROM showtime st
            JOIN movie m ON m.id = st.movie_id
            JOIN language l ON l.code = st.language_code
            JOIN screen sc ON sc.id = st.screen_id
            JOIN theater t ON t.id = sc.theater_id
            JOIN city c ON c.id = t.city_id
            """;

    /**
     * {@code q <% column} is true when q's word similarity to some extent of the column exceeds
     * {@code pg_trgm.word_similarity_threshold} (PostgreSQL default 0.6, which tolerates a
     * one-character typo in the sample titles). The GIN trigram indexes from V6 serve it.
     */
    private static final String TEXT_MATCH = "(:q <% m.title OR :q <% t.name)";

    private static final String RELEVANCE = "greatest(word_similarity(:q, m.title), word_similarity(:q, t.name))";

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MovieView> findMovies() {
        return jdbc.query(MOVIE_SELECT + MOVIE_GROUP + " ORDER BY m.title, m.id", CatalogQueryRepository::movie);
    }

    public Optional<MovieView> findMovie(long movieId) {
        return jdbc.query(MOVIE_SELECT + " WHERE m.id = :movieId" + MOVIE_GROUP,
                new MapSqlParameterSource("movieId", movieId), CatalogQueryRepository::movie).stream().findFirst();
    }

    public long countShowtimes(ShowtimeSearch search, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT count(*) " + SHOWTIME_FROM + where(search, now, params);
        return jdbc.queryForObject(sql, params, Long.class);
    }

    public List<ShowtimeSearchResult> searchShowtimes(ShowtimeSearch search, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String orderBy = search.q() == null
                ? " ORDER BY st.starts_at, t.name, st.id"
                : " ORDER BY " + RELEVANCE + " DESC, st.starts_at, st.id";
        String sql = """
                SELECT st.id, st.starts_at, st.ends_at,
                       m.id AS movie_id, m.title, m.certification, m.duration_minutes,
                       l.code AS language_code, l.name AS language_name,
                       t.id AS theater_id, t.name AS theater_name,
                       c.id AS city_id, c.name AS city_name, c.state_code, c.time_zone,
                       sc.id AS screen_id, sc.name AS screen_name
                """ + SHOWTIME_FROM + where(search, now, params) + orderBy + " LIMIT :limit OFFSET :offset";
        params.addValue("limit", search.size()).addValue("offset", search.offset());
        return jdbc.query(sql, params, (rs, row) -> {
            Instant startsAt = instant(rs, "starts_at");
            ZoneId zone = ZoneId.of(rs.getString("time_zone"));
            ZonedDateTime localStart = startsAt.atZone(zone);
            return new ShowtimeSearchResult(rs.getLong("id"), rs.getLong("movie_id"), rs.getString("title"),
                    rs.getString("certification"), rs.getInt("duration_minutes"), language(rs),
                    rs.getLong("theater_id"), rs.getString("theater_name"), rs.getLong("city_id"),
                    rs.getString("city_name"), rs.getString("state_code"), rs.getLong("screen_id"),
                    rs.getString("screen_name"), startsAt, instant(rs, "ends_at"), localStart.toLocalDate(),
                    localStart.toLocalTime(), zone.getId());
        });
    }

    public Optional<ShowtimeView> findShowtime(long showtimeId) {
        String sql = """
                SELECT st.id, st.status, st.starts_at, st.ends_at, st.movie_id,
                       l.code AS language_code, l.name AS language_name,
                       t.id AS theater_id, t.name AS theater_name, t.address,
                       c.id AS city_id, c.name AS city_name, c.state_code, c.time_zone,
                       sc.id AS screen_id, sc.name AS screen_name
                """ + SHOWTIME_FROM + " WHERE st.id = :showtimeId";
        return jdbc.query(sql, new MapSqlParameterSource("showtimeId", showtimeId), (rs, row) -> new DetailRow(
                rs.getString("status"), instant(rs, "starts_at"), instant(rs, "ends_at"),
                ZoneId.of(rs.getString("time_zone")), rs.getLong("movie_id"), language(rs),
                new TheaterView(rs.getLong("theater_id"), rs.getString("theater_name"), rs.getString("address"),
                        new CityView(rs.getLong("city_id"), rs.getString("city_name"), rs.getString("state_code"))),
                new ScreenView(rs.getLong("screen_id"), rs.getString("screen_name"))))
                .stream().findFirst()
                .map(detail -> {
                    ZonedDateTime localStart = detail.startsAt().atZone(detail.zone());
                    return new ShowtimeView(showtimeId, detail.status(), detail.startsAt(), detail.endsAt(),
                            localStart.toLocalDate(), localStart.toLocalTime(),
                            detail.endsAt().atZone(detail.zone()).toLocalTime(), detail.zone().getId(),
                            findMovie(detail.movieId()).orElseThrow(), detail.language(), detail.theater(),
                            detail.screen(), findPrices(showtimeId));
                });
    }

    private List<PriceView> findPrices(long showtimeId) {
        String sql = """
                SELECT se.category, ss.price, count(*) AS seat_count
                FROM show_seat ss
                JOIN seat se ON se.id = ss.seat_id
                WHERE ss.showtime_id = :showtimeId
                GROUP BY se.category, ss.price
                ORDER BY ss.price, se.category""";
        return jdbc.query(sql, new MapSqlParameterSource("showtimeId", showtimeId),
                (rs, row) -> new PriceView(rs.getString("category"), rs.getBigDecimal("price"), rs.getInt("seat_count")));
    }

    /**
     * The date filter is a half-open UTC range computed per row from the theater's city time zone,
     * so a 23:30 local show stays on its local date even though its UTC date is the next day.
     * Without an explicit date, "today" is also taken in each city's zone at {@code now}.
     */
    private static String where(ShowtimeSearch search, Instant now, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        params.addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        conditions.add("st.status = 'SCHEDULED'");
        conditions.add("st.starts_at > :now");

        String localDay;
        if (search.date() != null) {
            params.addValue("date", search.date(), Types.DATE);
            localDay = "CAST(:date AS date)";
        } else {
            localDay = "CAST(CAST(:now AS timestamptz) AT TIME ZONE c.time_zone AS date)";
        }
        conditions.add("st.starts_at >= (CAST(" + localDay + " AS timestamp) AT TIME ZONE c.time_zone)");
        conditions.add("st.starts_at < (CAST(" + localDay + " + 1 AS timestamp) AT TIME ZONE c.time_zone)");

        if (search.cityId() != null) {
            params.addValue("cityId", search.cityId());
            conditions.add("c.id = :cityId");
        }
        if (search.movieId() != null) {
            params.addValue("movieId", search.movieId());
            conditions.add("m.id = :movieId");
        }
        if (search.languageCode() != null) {
            params.addValue("languageCode", search.languageCode());
            conditions.add("st.language_code = :languageCode");
        }
        if (search.genre() != null) {
            params.addValue("genre", search.genre());
            conditions.add("""
                    EXISTS (SELECT 1 FROM movie_genre mg JOIN genre g ON g.id = mg.genre_id
                            WHERE mg.movie_id = m.id AND lower(g.name) = lower(:genre))""");
        }
        if (search.q() != null) {
            params.addValue("q", search.q());
            conditions.add(TEXT_MATCH);
        }
        return " WHERE " + String.join(" AND ", conditions);
    }

    private static MovieView movie(ResultSet rs, int row) throws SQLException {
        Array genres = rs.getArray("genres");
        List<String> genreNames = genres == null ? List.of() : Arrays.asList((String[]) genres.getArray());
        return new MovieView(rs.getLong("id"), rs.getString("title"), rs.getInt("duration_minutes"),
                rs.getString("certification"), rs.getObject("release_date", LocalDate.class), language(rs),
                genreNames);
    }

    private static LanguageView language(ResultSet rs) throws SQLException {
        return new LanguageView(rs.getString("language_code"), rs.getString("language_name"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record DetailRow(String status, Instant startsAt, Instant endsAt, ZoneId zone, long movieId,
            LanguageView language, TheaterView theater, ScreenView screen) {
    }
}
