package com.moviebooking.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.MutableClock;
import com.moviebooking.ShowtimeFixtures;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;

/**
 * Public discovery API against PostgreSQL with the V7 seed (sample showtimes at 13:00 and 16:30
 * America/Chicago from 2026-11-03). Requests carry no credentials unless stated. Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class DiscoveryApiIT {

    private static final String STARLIGHT = "Starlight Cinemas Downtown";
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 3);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private ShowtimeFixtures fixtures;
    private long dallas;

    @BeforeEach
    void setUp() {
        fixtures = new ShowtimeFixtures(jdbc);
        fixtures.deleteNonSeedShowtimes();
        clock.set(TestClockConfiguration.START);
        dallas = fixtures.cityId("Dallas");
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteNonSeedShowtimes();
        jdbc.update("UPDATE showtime SET status = 'SCHEDULED' WHERE status = 'CANCELLED'");
    }

    @Test
    void citiesAreListedByNameWithTheirTimeZone() throws Exception {
        mockMvc.perform(get("/api/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Austin", "Dallas", "Houston")))
                .andExpect(jsonPath("$[*].stateCode", everyItem(is("TX"))))
                .andExpect(jsonPath("$[*].timeZone", everyItem(is("America/Chicago"))))
                .andExpect(jsonPath("$[1].id").value((int) dallas));
    }

    @Test
    void moviesAreListedByTitleWithMetadata() throws Exception {
        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[*].title", contains("Coco", "Dangal", "Inception", "Interstellar", "Parasite",
                        "Roma", "Spirited Away", "The Dark Knight")))
                .andExpect(jsonPath("$[3].id").value((int) fixtures.movieId("Interstellar")))
                .andExpect(jsonPath("$[3].durationMinutes").value(169))
                .andExpect(jsonPath("$[3].certification").value("PG-13"))
                .andExpect(jsonPath("$[3].releaseDate").value("2014-11-07"))
                .andExpect(jsonPath("$[3].originalLanguage.code").value("en"))
                .andExpect(jsonPath("$[3].originalLanguage.name").value("English"))
                .andExpect(jsonPath("$[3].genres", contains("Adventure", "Drama", "Sci-Fi")))
                .andExpect(jsonPath("$[5].genres", contains("Drama")));
    }

    @Test
    void cityAndDateReturnOnlyThatCitysShowtimesOnThatLocalDate() throws Exception {
        JsonNode page = search(get("/api/showtimes").param("cityId", "" + dallas).param("date", "2026-11-03"));

        assertThat(page.get("totalItems").asLong()).isEqualTo(6);
        for (JsonNode item : page.get("items")) {
            assertThat(item.get("cityName").asText()).isEqualTo("Dallas");
            assertThat(item.get("localDate").asText()).isEqualTo("2026-11-03");
            assertThat(item.get("localStartTime").asText()).isIn("13:00:00", "13:00", "16:30:00", "16:30");
            assertThat(item.get("timeZone").asText()).isEqualTo("America/Chicago");
        }
        assertThat(ids(page)).isEqualTo(oracle(FIRST_DAY, "Dallas", null, null));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "-,      en, -",
            "-,      -,  Drama",
            "-,      en, Drama",
            "Dallas, en, Sci-Fi",
            "Dallas, ja, Animation",
            "Austin, -,  Thriller",
            "-,      ko, Animation"
    })
    void languageGenreAndCityFiltersCombine(String city, String language, String genre) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/showtimes").param("date", "2026-11-03").param("size", "50");
        if (city != null) {
            request.param("cityId", "" + fixtures.cityId(city));
        }
        if (language != null) {
            request.param("language", language);
        }
        if (genre != null) {
            request.param("genre", genre);
        }

        assertThat(ids(search(request))).isEqualTo(oracle(FIRST_DAY, city, language, genre));
    }

    @Test
    void combinedFiltersNarrowTheResultAndGenreIsCaseInsensitive() throws Exception {
        List<Long> all = ids(search(get("/api/showtimes").param("date", "2026-11-03").param("size", "50")));
        List<Long> combined = ids(search(get("/api/showtimes").param("date", "2026-11-03")
                .param("language", "EN").param("genre", "drama")));

        assertThat(combined).isNotEmpty().isEqualTo(oracle(FIRST_DAY, null, "en", "Drama"));
        assertThat(all).containsAll(combined).hasSizeGreaterThan(combined.size());
    }

    @Test
    void misspelledMovieTitleStillFindsTheMovie() throws Exception {
        JsonNode page = search(get("/api/showtimes").param("date", "2026-11-03").param("q", "Intersteller"));

        assertThat(page.get("totalItems").asLong()).isPositive();
        assertThat(texts(page, "movieTitle")).containsOnly("Interstellar");
    }

    @ParameterizedTest
    @CsvSource({ "Starlite, Starlight Cinemas Downtown", "Galeria, Galleria Screens", "bayou, Bayou Picture House" })
    void misspelledOrPartialTheaterNameFindsTheTheater(String query, String theater) throws Exception {
        JsonNode page = search(get("/api/showtimes").param("date", "2026-11-03").param("q", query));

        assertThat(page.get("totalItems").asLong()).isPositive();
        assertThat(texts(page, "theaterName")).containsOnly(theater);
    }

    @Test
    void textSearchCombinesWithOtherFiltersAndUnrelatedTextFindsNothing() throws Exception {
        JsonNode inDallas = search(get("/api/showtimes").param("date", "2026-11-03")
                .param("cityId", "" + dallas).param("q", "Galeria"));
        assertThat(inDallas.get("totalItems").asLong()).isZero();

        mockMvc.perform(get("/api/showtimes").param("date", "2026-11-03").param("q", "xyzzy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void resultsArePaginatedWithoutGapsOrDuplicates() throws Exception {
        MockHttpServletRequestBuilder base = get("/api/showtimes").param("cityId", "" + dallas)
                .param("date", "2026-11-03").param("size", "4");

        JsonNode first = search(base.param("page", "0"));
        JsonNode second = search(get("/api/showtimes").param("cityId", "" + dallas).param("date", "2026-11-03")
                .param("size", "4").param("page", "1"));
        JsonNode beyond = search(get("/api/showtimes").param("cityId", "" + dallas).param("date", "2026-11-03")
                .param("size", "4").param("page", "2"));

        assertThat(first.get("size").asInt()).isEqualTo(4);
        assertThat(first.get("totalItems").asLong()).isEqualTo(6);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(first)).hasSize(4);
        assertThat(ids(second)).hasSize(2);
        assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.get("totalItems").asLong()).isEqualTo(6);

        List<Long> joined = new ArrayList<>(ids(first));
        joined.addAll(ids(second));
        assertThat(joined).isEqualTo(oracle(FIRST_DAY, "Dallas", null, null));
    }

    @Test
    void pageSizeIsCappedAndInvalidPagingIsRejected() throws Exception {
        mockMvc.perform(get("/api/showtimes").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(ShowtimeSearch.MAX_PAGE_SIZE));
        mockMvc.perform(get("/api/showtimes").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.parameter").value("size"));
        mockMvc.perform(get("/api/showtimes").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.parameter").value("page"));
        mockMvc.perform(get("/api/showtimes").param("date", "2026-13-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/showtimes").param("cityId", "dallas"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void lateEveningShowStaysOnItsLocalDateNotItsUtcDate() throws Exception {
        // 23:30 CST on Nov 5 is 05:30 UTC on Nov 6; 00:15 CST on Nov 6 is 06:15 UTC on Nov 6.
        long lateNov5 = fixtures.insertShowtime(STARLIGHT, "Screen 1", "Coco", Instant.parse("2026-11-06T05:30:00Z"));
        long earlyNov6 = fixtures.insertShowtime(STARLIGHT, "Screen 2", "Coco", Instant.parse("2026-11-06T06:15:00Z"));

        List<Long> nov5 = ids(search(dallasOn("2026-11-05").param("q", "Coco")));
        List<Long> nov6 = ids(search(dallasOn("2026-11-06").param("q", "Coco")));

        assertThat(nov5).contains(lateNov5).doesNotContain(earlyNov6);
        assertThat(nov6).contains(earlyNov6).doesNotContain(lateNov5);

        mockMvc.perform(get("/api/showtimes/{id}", lateNov5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value("2026-11-06T05:30:00Z"))
                .andExpect(jsonPath("$.localDate").value("2026-11-05"))
                .andExpect(jsonPath("$.localStartTime").value("23:30:00"));
    }

    @Test
    void localDateUsesDaylightSavingTimeWhenInEffect() throws Exception {
        // 2027-03-20 is in CDT (UTC-5): 23:30 local is 04:30 UTC the next day, not 05:30.
        long cdtLate = fixtures.insertShowtime(STARLIGHT, "Screen 1", "Coco", Instant.parse("2027-03-21T04:30:00Z"));

        assertThat(ids(search(dallasOn("2027-03-20")))).containsExactly(cdtLate);
        assertThat(ids(search(dallasOn("2027-03-21")))).isEmpty();
    }

    @Test
    void withoutADateTodayIsTheTheatersLocalDate() throws Exception {
        // 04:00 UTC on Nov 4 is still 22:00 on Nov 3 in Dallas.
        clock.set(Instant.parse("2026-11-04T04:00:00Z"));
        long tonight = fixtures.insertShowtime(STARLIGHT, "Screen 1", "Coco", Instant.parse("2026-11-04T05:30:00Z"));

        JsonNode page = search(get("/api/showtimes").param("cityId", "" + dallas));

        assertThat(ids(page)).containsExactly(tonight);
        assertThat(page.get("items").get(0).get("localDate").asText()).isEqualTo("2026-11-03");
    }

    @Test
    void startedAndCancelledShowtimesAreNotListedButStillHaveDetails() throws Exception {
        clock.set(Instant.parse("2026-11-03T19:30:00Z")); // 13:30 in Dallas: the 13:00 shows have started
        List<Long> open = ids(search(dallasOn("2026-11-03")));
        assertThat(open).hasSize(3).isEqualTo(oracle(FIRST_DAY, "Dallas", null, null));

        long cancelled = open.getFirst();
        jdbc.update("UPDATE showtime SET status = 'CANCELLED' WHERE id = ?", cancelled);

        assertThat(ids(search(dallasOn("2026-11-03")))).hasSize(2).doesNotContain(cancelled);
        mockMvc.perform(get("/api/showtimes/{id}", cancelled))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void showtimeDetailHasMovieVenueLocalTimesAndPrices() throws Exception {
        long showtime = jdbc.queryForObject("""
                SELECT st.id FROM showtime st JOIN screen s ON s.id = st.screen_id JOIN theater t ON t.id = s.theater_id
                WHERE t.name = ? AND s.name = 'Screen 1' ORDER BY st.starts_at LIMIT 1""", Long.class, STARLIGHT);
        JsonNode movie = objectMapper.readTree(jdbc.queryForObject("""
                SELECT json_build_object('title', m.title, 'duration', m.duration_minutes)::text
                FROM showtime st JOIN movie m ON m.id = st.movie_id WHERE st.id = ?""", String.class, showtime));
        Instant endsAt = jdbc.queryForObject("SELECT ends_at FROM showtime WHERE id = ?", Instant.class, showtime);

        mockMvc.perform(get("/api/showtimes/{id}", showtime))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showtimeId").value((int) showtime))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.startsAt").value("2026-11-03T19:00:00Z"))
                .andExpect(jsonPath("$.endsAt").value(endsAt.toString()))
                .andExpect(jsonPath("$.localDate").value("2026-11-03"))
                .andExpect(jsonPath("$.localStartTime").value("13:00:00"))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.movie.title").value(movie.get("title").asText()))
                .andExpect(jsonPath("$.movie.durationMinutes").value(movie.get("duration").asInt()))
                .andExpect(jsonPath("$.movie.genres").isNotEmpty())
                .andExpect(jsonPath("$.language.code").isNotEmpty())
                .andExpect(jsonPath("$.theater.name").value(STARLIGHT))
                .andExpect(jsonPath("$.theater.address").value("1200 Elm St, Dallas, TX"))
                .andExpect(jsonPath("$.theater.city.name").value("Dallas"))
                .andExpect(jsonPath("$.theater.city.stateCode").value("TX"))
                .andExpect(jsonPath("$.screen.name").value("Screen 1"))
                .andExpect(jsonPath("$.prices[*].category", contains("STANDARD", "PREMIUM")))
                .andExpect(jsonPath("$.prices[*].price", contains(12.5, 16.0)))
                .andExpect(jsonPath("$.prices[*].seatCount", contains(40, 20)));
    }

    @Test
    void unknownShowtimeIsNotFoundInTheErrorFormat() throws Exception {
        mockMvc.perform(get("/api/showtimes/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anonymousVisitorsBrowseSeatMapsButMustLogInToHoldPayOrViewBookings() throws Exception {
        long showtime = oracle(FIRST_DAY, "Dallas", null, null).getFirst();
        long seat = jdbc.queryForObject("SELECT min(seat_id) FROM show_seat WHERE showtime_id = ?", Long.class,
                showtime);

        mockMvc.perform(get("/api/showtimes/{id}/seats", showtime))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showtimeId").value((int) showtime))
                .andExpect(jsonPath("$.seats").isNotEmpty());

        mockMvc.perform(post("/api/showtimes/{id}/holds", showtime).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[" + seat + "]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(delete("/api/holds/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/holds/{id}/payment", UUID.randomUUID()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"SUCCESS\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/payments/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/bookings/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_hold", Long.class)).isZero();

        // The same browsing endpoints also work with credentials.
        mockMvc.perform(get("/api/showtimes/{id}/seats", showtime).with(httpBasic("alice", "alice-demo-pass")))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder dallasOn(String date) {
        return get("/api/showtimes").param("cityId", "" + dallas).param("date", date).param("size", "50");
    }

    private JsonNode search(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<Long> ids(JsonNode page) {
        return StreamSupport.stream(page.get("items").spliterator(), false)
                .map(item -> item.get("showtimeId").asLong()).toList();
    }

    private static List<String> texts(JsonNode page, String field) {
        return StreamSupport.stream(page.get("items").spliterator(), false).map(item -> item.get(field).asText())
                .toList();
    }

    /**
     * Expected ids, independent of the production query: the local date is computed with a fixed
     * America/Chicago zone, and filters are applied in plain SQL. Same order as the API without q.
     */
    private List<Long> oracle(LocalDate localDate, String city, String language, String genre) {
        List<Object> args = new ArrayList<>(Arrays.asList(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), localDate));
        StringBuilder sql = new StringBuilder("""
                SELECT st.id FROM showtime st
                JOIN screen sc ON sc.id = st.screen_id
                JOIN theater t ON t.id = sc.theater_id
                JOIN city c ON c.id = t.city_id
                WHERE st.status = 'SCHEDULED' AND st.starts_at > ?
                  AND (st.starts_at AT TIME ZONE 'America/Chicago')::date = ?""");
        if (city != null) {
            sql.append(" AND c.name = ?");
            args.add(city);
        }
        if (language != null) {
            sql.append(" AND st.language_code = ?");
            args.add(language);
        }
        if (genre != null) {
            sql.append(" AND st.movie_id IN (SELECT mg.movie_id FROM movie_genre mg JOIN genre g ON g.id = mg.genre_id"
                    + " WHERE g.name = ?)");
            args.add(genre);
        }
        sql.append(" ORDER BY st.starts_at, t.name, st.id");
        return jdbc.queryForList(sql.toString(), Long.class, args.toArray());
    }
}
