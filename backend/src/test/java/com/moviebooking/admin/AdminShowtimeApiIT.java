package com.moviebooking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.MutableClock;
import com.moviebooking.ShowtimeFixtures;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;
import com.moviebooking.config.ApiException;
import com.moviebooking.security.AppUserDetailsService;
import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.NewShowtime;

/** Theater-admin showtime creation against PostgreSQL with the seeded demo users. Requires Docker. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class AdminShowtimeApiIT {

    private static final RequestPostProcessor DALLAS_ADMIN = httpBasic("admin.dallas", "dallas-admin-demo");
    private static final RequestPostProcessor HOUSTON_ADMIN = httpBasic("admin.houston", "houston-admin-demo");
    private static final RequestPostProcessor ALICE = httpBasic("alice", "alice-demo-pass");
    private static final String STARLIGHT = "Starlight Cinemas Downtown";
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminShowtimeService adminShowtimeService;

    @Autowired
    private AppUserDetailsService users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ShowtimeFixtures fixtures;
    private long starlightScreen1;
    private long starlightScreen2;
    private long interstellar;
    private long coco;

    @BeforeEach
    void setUp() {
        fixtures = new ShowtimeFixtures(jdbc);
        fixtures.deleteNonSeedShowtimes();
        clock.set(TestClockConfiguration.START);
        starlightScreen1 = fixtures.screenId(STARLIGHT, "Screen 1");
        starlightScreen2 = fixtures.screenId(STARLIGHT, "Screen 2");
        interstellar = fixtures.movieId("Interstellar");
        coco = fixtures.movieId("Coco");
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteNonSeedShowtimes();
    }

    @Test
    void assignedAdminCreatesShowtimeWithLocalStartAndFullSeatInventory() throws Exception {
        // 20:00 CST on Nov 10 is 02:00 UTC on Nov 11; Interstellar runs 169 min plus 15 min cleaning.
        long id = createdId(create(DALLAS_ADMIN, request(interstellar, starlightScreen2, "2026-11-10T20:00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.theaterId").value((int) fixtures.theaterId(STARLIGHT)))
                .andExpect(jsonPath("$.screenId").value((int) starlightScreen2))
                .andExpect(jsonPath("$.movieId").value((int) interstellar))
                .andExpect(jsonPath("$.languageCode").value("en"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.startsAt").value("2026-11-11T02:00:00Z"))
                .andExpect(jsonPath("$.endsAt").value("2026-11-11T05:04:00Z"))
                .andExpect(jsonPath("$.localDate").value("2026-11-10"))
                .andExpect(jsonPath("$.localStartTime").value("20:00:00"))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.seatCount").value(40)));

        assertThat(jdbc.queryForObject("SELECT created_at FROM showtime WHERE id = ?", Instant.class, id))
                .isEqualTo(TestClockConfiguration.START);
        assertThat(jdbc.queryForList("""
                SELECT se.category || ':' || ss.price || ':' || ss.status || ':' || count(*)
                FROM show_seat ss JOIN seat se ON se.id = ss.seat_id
                WHERE ss.showtime_id = ? GROUP BY se.category, ss.price, ss.status ORDER BY 1""", String.class, id))
                .containsExactly("PREMIUM:15.50:AVAILABLE:16", "STANDARD:11.00:AVAILABLE:24");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat WHERE screen_id = ?", Long.class, starlightScreen2))
                .isEqualTo(40);
    }

    @Test
    void createdShowtimeIsSearchableBrowsableAndHoldable() throws Exception {
        long id = createdId(create(DALLAS_ADMIN, request(interstellar, starlightScreen2, "2026-11-10T20:00"))
                .andExpect(status().isCreated()));

        mockMvc.perform(get("/api/showtimes").param("cityId", "" + fixtures.cityId("Dallas"))
                        .param("date", "2026-11-10").param("q", "Interstelar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.showtimeId == " + id + ")].localStartTime").value("20:00:00"));
        mockMvc.perform(get("/api/showtimes/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prices[*].price", contains(11.0, 15.5)));
        mockMvc.perform(get("/api/showtimes/{id}/seats", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats", hasSize(40)));

        long seat = jdbc.queryForObject("SELECT min(seat_id) FROM show_seat WHERE showtime_id = ?", Long.class, id);
        mockMvc.perform(post("/api/showtimes/{id}/holds", id).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[" + seat + "]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void onlyAdminsAssignedToTheScreensTheaterMayCreateShowtimes() throws Exception {
        Map<String, Object> body = request(interstellar, starlightScreen2, "2026-11-10T20:00");

        mockMvc.perform(post("/api/admin/showtimes").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        create(ALICE, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        create(HOUSTON_ADMIN, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(fixtures.nonSeedShowtimeCount()).isZero();

        long bayouScreen = fixtures.screenId("Bayou Picture House", "Screen 1");
        create(HOUSTON_ADMIN, request(coco, bayouScreen, "2026-11-10T20:00")).andExpect(status().isCreated());
        create(DALLAS_ADMIN, request(coco, bayouScreen, "2026-11-11T20:00")).andExpect(status().isForbidden());
        assertThat(fixtures.nonSeedShowtimeCount()).isEqualTo(1);
    }

    @Test
    void overlappingShowtimeOnTheSameScreenIsRejected() throws Exception {
        // The seeded 13:00 show on Nov 3 still occupies the screen at 14:00.
        create(DALLAS_ADMIN, request(coco, starlightScreen1, "2026-11-03T14:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOWTIME_OVERLAP"))
                .andExpect(jsonPath("$.details.screenId").value((int) starlightScreen1));
        // The other screen of the same theater is free at that time.
        create(DALLAS_ADMIN, request(coco, starlightScreen2, "2026-11-03T20:00")).andExpect(status().isCreated());

        assertThat(fixtures.nonSeedShowtimeCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM show_seat ss JOIN showtime st ON st.id = ss.showtime_id"
                + " WHERE st.screen_id = ? AND st.starts_at = ?", Long.class, starlightScreen1,
                OffsetDateTime.parse("2026-11-03T20:00:00Z"))).isZero();
    }

    @Test
    void backToBackShowtimesAreAllowedButOneMinuteEarlierOverlaps() throws Exception {
        Instant lastSeededEnd = jdbc.queryForObject("""
                SELECT max(ends_at) FROM showtime
                WHERE screen_id = ? AND starts_at < TIMESTAMPTZ '2026-11-04 06:00:00+00'""", Instant.class,
                starlightScreen1);
        LocalDateTime localEnd = LocalDateTime.ofInstant(lastSeededEnd, CHICAGO);

        create(DALLAS_ADMIN, request(coco, starlightScreen1, localEnd.minusMinutes(1).toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOWTIME_OVERLAP"));
        create(DALLAS_ADMIN, request(coco, starlightScreen1, localEnd.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startsAt").value(lastSeededEnd.toString()));
    }

    @Test
    void concurrentOverlappingCreatesProduceExactlyOneShowtime() throws Exception {
        AuthenticatedUser admin = users.loadUserByUsername("admin.dallas");
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                // Different start minutes, all overlapping each other on the same screen.
                NewShowtime request = new NewShowtime(coco, starlightScreen2, "en",
                        LocalDateTime.parse("2026-11-10T20:00").plusMinutes(i), new BigDecimal("12.50"),
                        new BigDecimal("16.00"));
                outcomes.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        adminShowtimeService.createShowtime(admin, request);
                        return "CREATED";
                    } catch (ApiException ex) {
                        return ex.code();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).containsOnly("CREATED", "SHOWTIME_OVERLAP");
            assertThat(results).filteredOn("CREATED"::equals).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(fixtures.nonSeedShowtimeCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT showtime_id) FROM show_seat ss JOIN showtime st"
                + " ON st.id = ss.showtime_id WHERE st.created_at = ?", Long.class,
                OffsetDateTime.parse("2026-11-02T12:00:00Z"))).isEqualTo(1);
    }

    @Test
    void lockWaitOnTheScreenIsBoundedAndReportedAsScreenBusy() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> blocker = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM screen WHERE id = ? FOR UPDATE", starlightScreen2);
                locked.countDown();
                try {
                    finish.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            create(DALLAS_ADMIN, request(coco, starlightScreen2, "2026-11-10T20:00"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SCREEN_BUSY"));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            assertThat(waited).isBetween(Duration.ofMillis(1900), Duration.ofSeconds(10));

            finish.countDown();
            blocker.get(30, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
        assertThat(fixtures.nonSeedShowtimeCount()).isZero();
        create(DALLAS_ADMIN, request(coco, starlightScreen2, "2026-11-10T20:00")).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-11-02T05:00", // 11:00 UTC: before the test clock's 12:00 UTC
            "2027-03-14T02:30", // skipped by the spring-forward change
            "2027-11-07T01:30"  // occurs twice at the fall-back change
    })
    void pastAndDaylightSavingAmbiguousStartTimesAreRejected(String local) throws Exception {
        create(DALLAS_ADMIN, request(coco, starlightScreen2, local))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_START_TIME"))
                .andExpect(jsonPath("$.details.timeZone").value("America/Chicago"));
        assertThat(fixtures.nonSeedShowtimeCount()).isZero();
    }

    @Test
    void invalidRequestsAreRejectedWithTheOffendingFields() throws Exception {
        create(DALLAS_ADMIN, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.fields", containsInAnyOrder("movieId", "screenId", "languageCode",
                        "startsAtLocal", "standardPrice", "premiumPrice")));

        Map<String, Object> unknownMovie = request(999_999L, starlightScreen2, "2026-11-10T20:00");
        create(DALLAS_ADMIN, unknownMovie)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields", containsInAnyOrder("movieId")));

        Map<String, Object> unknownScreen = request(coco, 999_999L, "2026-11-10T20:00");
        create(DALLAS_ADMIN, unknownScreen)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields", containsInAnyOrder("screenId")));

        Map<String, Object> unknownLanguage = request(coco, starlightScreen2, "2026-11-10T20:00");
        unknownLanguage.put("languageCode", "xx");
        create(DALLAS_ADMIN, unknownLanguage)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields", containsInAnyOrder("languageCode")));

        Map<String, Object> badPrice = request(coco, starlightScreen2, "2026-11-10T20:00");
        badPrice.put("premiumPrice", 12.345);
        create(DALLAS_ADMIN, badPrice)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields", containsInAnyOrder("premiumPrice")));

        mockMvc.perform(post("/api/admin/showtimes").with(DALLAS_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAtLocal\":\"tomorrow\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(fixtures.nonSeedShowtimeCount()).isZero();
    }

    private Map<String, Object> request(long movieId, long screenId, String startsAtLocal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("movieId", movieId);
        body.put("screenId", screenId);
        body.put("languageCode", "en");
        body.put("startsAtLocal", startsAtLocal);
        body.put("standardPrice", new BigDecimal("11.00"));
        body.put("premiumPrice", new BigDecimal("15.50"));
        return body;
    }

    private ResultActions create(RequestPostProcessor user, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/admin/showtimes").with(user).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long createdId(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("showtimeId").asLong();
    }
}
