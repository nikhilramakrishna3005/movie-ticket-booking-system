package com.moviebooking.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.moviebooking.MutableClock;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;
import com.moviebooking.booking.HoldFixtures;
import com.moviebooking.config.TheaterZone;

/**
 * City, movie and showtime discovery against PostgreSQL. Date assertions use PostgreSQL's own
 * {@code AT TIME ZONE 'America/Chicago'} as the oracle, not the application's zone helper.
 * Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class DiscoveryApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private HoldFixtures db;

    @BeforeEach
    void setUp() {
        db = new HoldFixtures(jdbc);
        db.reset();
        clock.set(TestClockConfiguration.START);
    }

    @AfterEach
    void tearDown() {
        db.reset();
    }

    @Test
    void listsCitiesInNameOrder() throws Exception {
        mockMvc.perform(get("/api/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Austin", "Dallas", "Houston")))
                .andExpect(jsonPath("$[0].stateCode").value("TX"))
                .andExpect(jsonPath("$[1].name").value("Dallas"));
    }

    @Test
    void listsMoviesWithLanguageGenresAndRuntime() throws Exception {
        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[?(@.title == 'Interstellar')].language.code", contains("en")))
                .andExpect(jsonPath("$[?(@.title == 'Interstellar')].language.name", contains("English")))
                .andExpect(jsonPath("$[?(@.title == 'Interstellar')].durationMinutes", contains(169)))
                .andExpect(jsonPath("$[?(@.title == 'Interstellar')].certification", contains("PG-13")))
                .andExpect(jsonPath("$[?(@.title == 'Interstellar')].releaseDate", contains("2014-11-07")))
                .andExpect(jsonPath("$[?(@.title == 'Interstellar')].genres[*]",
                        containsInAnyOrder("Adventure", "Drama", "Sci-Fi")))
                .andExpect(jsonPath("$[?(@.title == 'Spirited Away')].language.code", contains("ja")));
    }

    @Test
    void cityAndDateFilterMatchesTheTheaterLocalDay() throws Exception {
        long dallas = cityId("Dallas");
        long expected = jdbc.queryForObject("""
                SELECT count(*) FROM showtime st
                JOIN screen sc ON sc.id = st.screen_id
                JOIN theater t ON t.id = sc.theater_id
                WHERE t.city_id = ? AND st.status = 'SCHEDULED'
                  AND (st.starts_at AT TIME ZONE 'America/Chicago')::date = DATE '2026-11-03'""",
                Long.class, dallas);

        mockMvc.perform(get("/api/showtimes").param("city", String.valueOf(dallas)).param("date", "2026-11-03")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-03"))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.totalElements").value(expected))
                .andExpect(jsonPath("$.results", hasSize((int) expected)))
                .andExpect(jsonPath("$.results[*].theater.city",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("Dallas"))))
                .andExpect(jsonPath("$.results[*].theater.stateCode",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("TX"))));
        assertThat(expected).isPositive();
    }

    @Test
    void languageAndGenreFiltersCombine() throws Exception {
        long dallas = cityId("Dallas");
        long expected = jdbc.queryForObject("""
                SELECT count(*) FROM showtime st
                JOIN movie m ON m.id = st.movie_id
                JOIN screen sc ON sc.id = st.screen_id
                JOIN theater t ON t.id = sc.theater_id
                WHERE t.city_id = ? AND st.status = 'SCHEDULED'
                  AND (st.starts_at AT TIME ZONE 'America/Chicago')::date = DATE '2026-11-03'
                  AND st.language_code = 'en'
                  AND EXISTS (SELECT 1 FROM movie_genre mg JOIN genre g ON g.id = mg.genre_id
                              WHERE mg.movie_id = m.id AND g.name = 'Sci-Fi')""", Long.class, dallas);

        mockMvc.perform(get("/api/showtimes")
                        .param("city", String.valueOf(dallas))
                        .param("date", "2026-11-03")
                        .param("language", "en")
                        .param("genre", "sci-fi")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(expected))
                .andExpect(jsonPath("$.results[*].language.code", hasSize((int) expected)))
                .andExpect(jsonPath("$.results[*].movie.title",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.in(List.of("Interstellar", "Inception")))))
                .andExpect(jsonPath("$.results[*].language.code",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("en"))));
        assertThat(expected).isPositive();
    }

    @Test
    void movieAndTheaterTyposResolveToTheIntendedName() throws Exception {
        mockMvc.perform(get("/api/showtimes").param("date", "2026-11-03").param("q", "Intersteller").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.results[*].movie.title",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("Interstellar"))));

        mockMvc.perform(get("/api/showtimes").param("date", "2026-11-03").param("q", "Starlght").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.results[*].theater.name",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("Starlight Cinemas Downtown"))));

        mockMvc.perform(get("/api/showtimes").param("date", "2026-11-03").param("q", "Bayuo").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].theater.name",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("Bayou Picture House"))));
    }

    @Test
    void unknownNameReturnsAnEmptyPage() throws Exception {
        mockMvc.perform(get("/api/showtimes").param("date", "2026-11-03").param("q", "zzzzqqqq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.results", hasSize(0)));
    }

    @Test
    void pagesAreOrderedAndThePageSizeIsCapped() throws Exception {
        String first = mockMvc.perform(get("/api/showtimes").param("date", "2026-11-03").param("size", "1").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/showtimes").param("date", "2026-11-03").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isNotEqualTo(first);

        // One seeded day has fewer showtimes than the cap, so add a full page's worth plus one.
        jdbc.update("""
                INSERT INTO showtime (screen_id, movie_id, language_code, starts_at, ends_at, status, created_at)
                SELECT sc.id, m.id, 'en',
                       (DATE '2027-02-02' + n * INTERVAL '20 minutes') AT TIME ZONE 'America/Chicago',
                       (DATE '2027-02-02' + n * INTERVAL '20 minutes' + INTERVAL '15 minutes')
                           AT TIME ZONE 'America/Chicago',
                       'SCHEDULED', TIMESTAMPTZ '2026-11-02 12:00:00+00'
                FROM screen sc
                CROSS JOIN generate_series(0, 59) AS n
                JOIN movie m ON m.title = 'Coco'""");
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM showtime
                WHERE status = 'SCHEDULED'
                  AND (starts_at AT TIME ZONE 'America/Chicago')::date = DATE '2027-02-02'""", Long.class);
        assertThat(total).isGreaterThan(ShowtimeSearch.MAX_PAGE_SIZE);

        mockMvc.perform(get("/api/showtimes").param("date", "2027-02-02").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(ShowtimeSearch.MAX_PAGE_SIZE))
                .andExpect(jsonPath("$.totalElements").value(total))
                .andExpect(jsonPath("$.results", hasSize(ShowtimeSearch.MAX_PAGE_SIZE)));
    }

    @Test
    void omittedDateIsTodayInChicagoRatherThanUtc() throws Exception {
        long dallas = cityId("Dallas");
        // 04:30Z is still November 2 in Chicago, but already November 3 in UTC. The seed starts on November 3.
        clock.set(Instant.parse("2026-11-03T04:30:00Z"));

        mockMvc.perform(get("/api/showtimes").param("city", String.valueOf(dallas)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-02"))
                .andExpect(jsonPath("$.totalElements").value(0));

        clock.set(Instant.parse("2026-11-03T18:00:00Z"));
        mockMvc.perform(get("/api/showtimes").param("city", String.valueOf(dallas)))
                .andExpect(jsonPath("$.date").value("2026-11-03"))
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void showtimesNearMidnightStayOnTheirChicagoDate() throws Exception {
        long screenId = screenId("Starlight Cinemas Downtown", "Screen 1");
        long movieId = movieId("Coco");
        long latePrevious = insert(screenId, movieId, LocalDateTime.parse("2026-11-02T23:30:00"));
        long justAfterMidnight = insert(screenId, movieId, LocalDateTime.parse("2026-11-03T00:30:00"));
        long lateSameDay = insert(screenId, movieId, LocalDateTime.parse("2026-11-03T23:30:00"));
        long nextDay = insert(screenId, movieId, LocalDateTime.parse("2026-11-04T00:30:00"));

        List<Long> ids = showtimeIds(mockMvc.perform(get("/api/showtimes").param("date", "2026-11-03")
                        .param("q", "Coco").param("size", "50"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(ids).contains(justAfterMidnight, lateSameDay).doesNotContain(latePrevious, nextDay);
        mockMvc.perform(get("/api/showtimes/{id}", justAfterMidnight))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localDate").value("2026-11-03"))
                .andExpect(jsonPath("$.localTime").value("00:30:00"))
                .andExpect(jsonPath("$.startsAt").value("2026-11-03T06:30:00Z"));
        mockMvc.perform(get("/api/showtimes/{id}", lateSameDay))
                .andExpect(jsonPath("$.localDate").value("2026-11-03"))
                .andExpect(jsonPath("$.localTime").value("23:30:00"))
                .andExpect(jsonPath("$.startsAt").value("2026-11-04T05:30:00Z"));
    }

    @Test
    void showtimeDetailReportsVenueLanguageAndLiveSeatPrices() throws Exception {
        long showtimeId = jdbc.queryForObject("""
                SELECT st.id FROM showtime st
                JOIN movie m ON m.id = st.movie_id
                WHERE m.title = 'Interstellar' AND (st.starts_at AT TIME ZONE 'America/Chicago')::date = DATE '2026-11-03'
                ORDER BY st.starts_at, st.id LIMIT 1""", Long.class);

        mockMvc.perform(get("/api/showtimes/{id}", showtimeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.localDate").value("2026-11-03"))
                .andExpect(jsonPath("$.movie.title").value("Interstellar"))
                .andExpect(jsonPath("$.movie.originalLanguage.code").value("en"))
                .andExpect(jsonPath("$.language.code").value("en"))
                .andExpect(jsonPath("$.theater.name").isNotEmpty())
                .andExpect(jsonPath("$.theater.address").isNotEmpty())
                .andExpect(jsonPath("$.screen.name").isNotEmpty())
                .andExpect(jsonPath("$.seats[?(@.category == 'STANDARD')].price", contains(12.5)))
                .andExpect(jsonPath("$.seats[?(@.category == 'PREMIUM')].price", contains(16.0)));

        int total = jdbc.queryForObject("SELECT count(*) FROM show_seat WHERE showtime_id = ?", Integer.class,
                showtimeId);
        assertThat(availableSeats(showtimeId)).isEqualTo(total);

        long seat = jdbc.queryForObject(
                "SELECT seat_id FROM show_seat WHERE showtime_id = ? ORDER BY seat_id LIMIT 1", Long.class, showtimeId);
        mockMvc.perform(post("/api/showtimes/{id}/holds", showtimeId).with(httpBasic("alice", "alice-demo-pass"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seat + "]}"))
                .andExpect(status().isCreated());
        assertThat(availableSeats(showtimeId)).isEqualTo(total - 1);

        clock.advance(java.time.Duration.ofMinutes(8));
        assertThat(availableSeats(showtimeId)).as("an expired hold is free again").isEqualTo(total);
    }

    @Test
    void anonymousBrowsingWorksAndBookingStillRequiresLogin() throws Exception {
        long showtimeId = jdbc.queryForObject("SELECT min(id) FROM showtime", Long.class);

        mockMvc.perform(get("/api/cities")).andExpect(status().isOk());
        mockMvc.perform(get("/api/movies")).andExpect(status().isOk());
        mockMvc.perform(get("/api/showtimes").param("date", "2026-11-03")).andExpect(status().isOk());
        mockMvc.perform(get("/api/showtimes/{id}", showtimeId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/showtimes/{id}/seats", showtimeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats").isArray())
                .andExpect(jsonPath("$.seats[0].price").exists());
        mockMvc.perform(post("/api/showtimes/{id}/holds", showtimeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/showtimes/999999999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/showtimes").param("date", "tomorrow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/showtimes").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/showtimes").param("genre", "Musical"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/showtimes").param("city", "999999999"))
                .andExpect(status().isNotFound());
    }

    private int availableSeats(long showtimeId) throws Exception {
        String body = mockMvc.perform(get("/api/showtimes/{id}", showtimeId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int sum = 0;
        for (com.fasterxml.jackson.databind.JsonNode seat : json(body).get("seats")) {
            sum += seat.get("available").asInt();
        }
        return sum;
    }

    private static List<Long> showtimeIds(String body) {
        List<Long> ids = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode result : json(body).get("results")) {
            ids.add(result.get("showtimeId").asLong());
        }
        return ids;
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long insert(long screenId, long movieId, LocalDateTime localStart) {
        Instant startsAt = TheaterZone.toInstant(localStart);
        return jdbc.queryForObject("""
                INSERT INTO showtime (screen_id, movie_id, language_code, starts_at, ends_at, status, created_at)
                VALUES (?, ?, 'en', ?, ?, 'SCHEDULED', TIMESTAMPTZ '2026-11-02 12:00:00+00')
                RETURNING id""", Long.class, screenId, movieId, Timestamp.from(startsAt),
                Timestamp.from(startsAt.plus(java.time.Duration.ofMinutes(30))));
    }

    private long cityId(String name) {
        return jdbc.queryForObject("SELECT id FROM city WHERE name = ? AND state_code = 'TX'", Long.class, name);
    }

    private long screenId(String theater, String screen) {
        return jdbc.queryForObject("""
                SELECT sc.id FROM screen sc JOIN theater t ON t.id = sc.theater_id
                WHERE t.name = ? AND sc.name = ?""", Long.class, theater, screen);
    }

    private long movieId(String title) {
        return jdbc.queryForObject("SELECT id FROM movie WHERE title = ?", Long.class, title);
    }

}
