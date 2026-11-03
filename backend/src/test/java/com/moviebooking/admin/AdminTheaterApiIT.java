package com.moviebooking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.MutableClock;
import com.moviebooking.ShowtimeFixtures;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;
import com.moviebooking.booking.HoldFixtures;
import com.moviebooking.config.ApiException;
import com.moviebooking.security.AppUserDetailsService;
import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.NewScreen;
import com.moviebooking.theater.NewShowtime;
import com.moviebooking.theater.SeatLayout;

/**
 * Theater-admin reads and screen management against PostgreSQL with the seeded demo users. The
 * Dallas admin is assigned only to Starlight Cinemas Downtown, the Houston admin only to Bayou
 * Picture House. Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class AdminTheaterApiIT {

    private static final RequestPostProcessor DALLAS_ADMIN = httpBasic("admin.dallas", "dallas-admin-demo");
    private static final RequestPostProcessor HOUSTON_ADMIN = httpBasic("admin.houston", "houston-admin-demo");
    private static final RequestPostProcessor ALICE = httpBasic("alice", "alice-demo-pass");
    private static final RequestPostProcessor BOB = httpBasic("bob", "bob-demo-pass");
    private static final String STARLIGHT = "Starlight Cinemas Downtown";
    private static final String BAYOU = "Bayou Picture House";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminTheaterService adminTheaterService;

    @Autowired
    private AdminShowtimeService adminShowtimeService;

    @Autowired
    private AppUserDetailsService users;

    private ShowtimeFixtures fixtures;
    private long starlight;
    private long bayou;
    private long starlightScreen1;
    private long starlightScreen2;
    private long bayouScreen;
    private long coco;

    @BeforeEach
    void setUp() {
        fixtures = new ShowtimeFixtures(jdbc);
        cleanUp();
        clock.set(TestClockConfiguration.START);
        starlight = fixtures.theaterId(STARLIGHT);
        bayou = fixtures.theaterId(BAYOU);
        starlightScreen1 = fixtures.screenId(STARLIGHT, "Screen 1");
        starlightScreen2 = fixtures.screenId(STARLIGHT, "Screen 2");
        bayouScreen = fixtures.screenId(BAYOU, "Screen 1");
        coco = fixtures.movieId("Coco");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        new HoldFixtures(jdbc).reset();
        fixtures.deleteNonSeedShowtimes();
        fixtures.deleteNonSeedScreens();
    }

    @Test
    void anAdminSeesOnlyTheTheatersAssignedToThem() throws Exception {
        mockMvc.perform(get("/api/admin/theaters").with(DALLAS_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].theaterId").value((int) starlight))
                .andExpect(jsonPath("$[0].name").value(STARLIGHT))
                .andExpect(jsonPath("$[0].address").value("1200 Elm St, Dallas, TX"))
                .andExpect(jsonPath("$[0].city.name").value("Dallas"))
                .andExpect(jsonPath("$[0].city.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$[0].screens[*].name", contains("Screen 1", "Screen 2")))
                // Seed layout: 6 x 10 and 5 x 8 seats, the last two rows premium.
                .andExpect(jsonPath("$[0].screens[0].rowCount").value(6))
                .andExpect(jsonPath("$[0].screens[0].seatCount").value(60))
                .andExpect(jsonPath("$[0].screens[0].standardSeats").value(40))
                .andExpect(jsonPath("$[0].screens[0].premiumSeats").value(20))
                .andExpect(jsonPath("$[0].screens[1].seatCount").value(40))
                .andExpect(jsonPath("$[0].screens[*].layoutLocked", contains(true, true)));

        mockMvc.perform(get("/api/admin/theaters").with(HOUSTON_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains(BAYOU)));
    }

    @Test
    void adminEndpointsNeedCredentialsAndTheAdminRole() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/api/admin/theaters"),
                get("/api/admin/theaters/{id}/showtimes", starlight),
                get("/api/admin/screens/{id}", starlightScreen1),
                json(post("/api/admin/theaters/{id}/screens", starlight), newScreen("Screen 9", rows(2, 5, 0))),
                json(put("/api/admin/screens/{id}/layout", starlightScreen1), Map.of("rows", rows(2, 5, 0))));
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            mockMvc.perform(request.with(ALICE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(screenCount(starlight)).isEqualTo(2);
    }

    @Test
    void dallasAdminCannotReadOrChangeHoustonTheatersOrScreens() throws Exception {
        mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", bayou).with(DALLAS_ADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/admin/screens/{id}", bayouScreen).with(DALLAS_ADMIN))
                .andExpect(status().isForbidden());
        // Scoping is decided before the body is read: even an invalid body gets 403, not 400.
        mockMvc.perform(json(post("/api/admin/theaters/{id}/screens", bayou).with(DALLAS_ADMIN), Map.of()))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/api/admin/theaters/{id}/screens", bayou).with(DALLAS_ADMIN),
                        newScreen("Screen 2", rows(2, 5, 0))))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(put("/api/admin/screens/{id}/layout", bayouScreen).with(DALLAS_ADMIN),
                        Map.of("rows", rows(2, 5, 0))))
                .andExpect(status().isForbidden());
        createShowtime(DALLAS_ADMIN, bayouScreen, "2026-11-10T20:00").andExpect(status().isForbidden());

        assertThat(screenCount(bayou)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat WHERE screen_id = ?", Long.class, bayouScreen))
                .isEqualTo(60);
        assertThat(fixtures.nonSeedShowtimeCount()).isZero();

        mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", bayou).with(HOUSTON_ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void unknownScreensAre404AndUnassignedTheaterIdsAre403() throws Exception {
        mockMvc.perform(get("/api/admin/screens/{id}", 999_999).with(DALLAS_ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(json(put("/api/admin/screens/{id}/layout", 999_999).with(DALLAS_ADMIN),
                        Map.of("rows", rows(2, 5, 0))))
                .andExpect(status().isNotFound());
        // Whether or not a theater exists is not revealed to admins who are not assigned to it.
        mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", 999_999).with(DALLAS_ADMIN))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/api/admin/theaters/{id}/screens", 999_999).with(DALLAS_ADMIN),
                        newScreen("Screen 3", rows(2, 5, 0))))
                .andExpect(status().isForbidden());
    }

    @Test
    void screenDetailListsEverySeatInRowOrder() throws Exception {
        mockMvc.perform(get("/api/admin/screens/{id}", starlightScreen2).with(DALLAS_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenId").value((int) starlightScreen2))
                .andExpect(jsonPath("$.theaterId").value((int) starlight))
                .andExpect(jsonPath("$.name").value("Screen 2"))
                .andExpect(jsonPath("$.layoutLocked").value(true))
                .andExpect(jsonPath("$.seats", hasSize(40)))
                .andExpect(jsonPath("$.seats[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.seats[0].seatNumber").value(1))
                .andExpect(jsonPath("$.seats[0].category").value("STANDARD"))
                .andExpect(jsonPath("$.seats[39].rowLabel").value("E"))
                .andExpect(jsonPath("$.seats[39].seatNumber").value(8))
                .andExpect(jsonPath("$.seats[39].category").value("PREMIUM"));
    }

    @Test
    void scheduleListsTheLocalDaysShowtimesWithPricesAndSales() throws Exception {
        long created = createdId(createShowtime(DALLAS_ADMIN, starlightScreen2, "2026-11-10T20:00")
                .andExpect(status().isCreated()), "showtimeId");
        List<Long> seats = jdbc.queryForList(
                "SELECT seat_id FROM show_seat WHERE showtime_id = ? ORDER BY seat_id LIMIT 3", Long.class, created);
        hold(ALICE, created, seats.subList(0, 2));
        String bobHold = hold(BOB, created, seats.subList(2, 3));
        mockMvc.perform(post("/api/holds/{id}/payment", bobHold).with(BOB).header("Idempotency-Key", "admin-it-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"SUCCESS\"}"))
                .andExpect(status().isCreated());

        // Seeded: 13:00 and 16:30 on both screens; then the new 20:00 show.
        mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", starlight).param("date", "2026-11-10")
                        .with(DALLAS_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theaterId").value((int) starlight))
                .andExpect(jsonPath("$.date").value("2026-11-10"))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.showtimes[*].localStartTime",
                        contains("13:00:00", "13:00:00", "16:30:00", "16:30:00", "20:00:00")))
                .andExpect(jsonPath("$.showtimes[*].screenName",
                        contains("Screen 1", "Screen 2", "Screen 1", "Screen 2", "Screen 2")))
                .andExpect(jsonPath("$.showtimes[0].standardPrice").value(12.5))
                .andExpect(jsonPath("$.showtimes[0].premiumPrice").value(16.0))
                .andExpect(jsonPath("$.showtimes[4].showtimeId").value((int) created))
                .andExpect(jsonPath("$.showtimes[4].movieId").value((int) coco))
                .andExpect(jsonPath("$.showtimes[4].movieTitle").value("Coco"))
                .andExpect(jsonPath("$.showtimes[4].languageCode").value("en"))
                .andExpect(jsonPath("$.showtimes[4].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.showtimes[4].startsAt").value("2026-11-11T02:00:00Z"))
                .andExpect(jsonPath("$.showtimes[4].localEndTime").value("22:00:00"))
                .andExpect(jsonPath("$.showtimes[4].standardPrice").value(11.0))
                .andExpect(jsonPath("$.showtimes[4].premiumPrice").value(15.5))
                .andExpect(jsonPath("$.showtimes[4].seatCount").value(40))
                .andExpect(jsonPath("$.showtimes[4].bookedSeats").value(1))
                .andExpect(jsonPath("$.showtimes[4].heldSeats").value(2));

        // Alice's hold lapses: her seats count as free again without any cleanup job.
        clock.advance(Duration.ofMinutes(9));
        mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", starlight).param("date", "2026-11-10")
                        .with(DALLAS_ADMIN))
                .andExpect(jsonPath("$.showtimes[4].bookedSeats").value(1))
                .andExpect(jsonPath("$.showtimes[4].heldSeats").value(0));
    }

    @Test
    void scheduleIncludesStartedAndCancelledShowtimesAndDefaultsToTodayInTheTheatersZone() throws Exception {
        // 12:00 UTC on Nov 2 is 06:00 in Chicago, the day before the seeded showtimes begin.
        mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", starlight).with(DALLAS_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-02"))
                .andExpect(jsonPath("$.showtimes", hasSize(0)));

        long cancelled = jdbc.queryForObject("""
                SELECT min(id) FROM showtime WHERE screen_id = ? AND starts_at = TIMESTAMPTZ '2026-11-03 22:30:00+00'""",
                Long.class, starlightScreen1);
        jdbc.update("UPDATE showtime SET status = 'CANCELLED' WHERE id = ?", cancelled);
        clock.set(Instant.parse("2026-11-03T20:00:00Z"));

        mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", starlight).with(DALLAS_ADMIN))
                .andExpect(jsonPath("$.date").value("2026-11-03"))
                .andExpect(jsonPath("$.showtimes", hasSize(4)))
                .andExpect(jsonPath("$.showtimes[?(@.showtimeId == " + cancelled + ")].status").value("CANCELLED"));
    }

    @Test
    void theLocalDayFollowsDaylightSavingRules() throws Exception {
        // 2027-03-14 springs forward in Chicago, so that local day is 23 hours long.
        createShowtime(DALLAS_ADMIN, starlightScreen1, "2027-03-14T00:30").andExpect(status().isCreated());
        createShowtime(DALLAS_ADMIN, starlightScreen1, "2027-03-14T23:00").andExpect(status().isCreated());
        createShowtime(DALLAS_ADMIN, starlightScreen2, "2027-03-15T00:30").andExpect(status().isCreated());
        createShowtime(DALLAS_ADMIN, starlightScreen2, "2027-03-13T23:30").andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", starlight).param("date", "2027-03-14")
                        .with(DALLAS_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showtimes[*].localStartTime", contains("00:30:00", "23:00:00")))
                .andExpect(jsonPath("$.showtimes[*].startsAt",
                        contains("2027-03-14T06:30:00Z", "2027-03-15T04:00:00Z")));
    }

    @Test
    void scheduleDateMustBeAValidDateInRange() throws Exception {
        for (String date : List.of("1999-12-31", "2101-01-01")) {
            mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", starlight).param("date", date)
                            .with(DALLAS_ADMIN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.details.parameter").value("date"));
        }
        mockMvc.perform(get("/api/admin/theaters/{id}/showtimes", starlight).param("date", "tomorrow")
                        .with(DALLAS_ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void anAdminAddsAScreenThatCanBeScheduledAndThenItsLayoutIsFixed() throws Exception {
        JsonNode screen = body(mockMvc.perform(json(post("/api/admin/theaters/{id}/screens", starlight)
                        .with(DALLAS_ADMIN), newScreen("  Screen 3  ", rows(4, 8, 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Screen 3"))
                .andExpect(jsonPath("$.theaterId").value((int) starlight))
                .andExpect(jsonPath("$.layoutLocked").value(false))
                .andExpect(jsonPath("$.seats", hasSize(32)))
                .andExpect(jsonPath("$.seats[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.seats[31].rowLabel").value("D"))
                .andExpect(jsonPath("$.seats[31].seatNumber").value(8))
                .andExpect(jsonPath("$.seats[31].category").value("PREMIUM")));
        long screenId = screen.get("screenId").asLong();

        mockMvc.perform(get("/api/admin/theaters").with(DALLAS_ADMIN))
                .andExpect(jsonPath("$[0].screens[*].name", contains("Screen 1", "Screen 2", "Screen 3")))
                .andExpect(jsonPath("$[0].screens[2].seatCount").value(32))
                .andExpect(jsonPath("$[0].screens[2].premiumSeats").value(8))
                .andExpect(jsonPath("$[0].screens[2].layoutLocked").value(false));

        long showtime = createdId(createShowtime(DALLAS_ADMIN, screenId, "2026-11-10T20:00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seatCount").value(32)), "showtimeId");
        mockMvc.perform(get("/api/showtimes/{id}/seats", showtime))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats", hasSize(32)));

        mockMvc.perform(json(put("/api/admin/screens/{id}/layout", screenId).with(DALLAS_ADMIN),
                        Map.of("rows", rows(2, 5, 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCREEN_HAS_SHOWTIMES"))
                .andExpect(jsonPath("$.details.screenId").value((int) screenId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat WHERE screen_id = ?", Long.class, screenId))
                .isEqualTo(32);
        mockMvc.perform(get("/api/admin/screens/{id}", screenId).with(DALLAS_ADMIN))
                .andExpect(jsonPath("$.layoutLocked").value(true));
    }

    @Test
    void aLayoutCanBeReplacedOnlyBeforeTheScreensFirstShowtime() throws Exception {
        long screenId = createdId(mockMvc.perform(json(post("/api/admin/theaters/{id}/screens", starlight)
                .with(DALLAS_ADMIN), newScreen("Screen 3", rows(2, 5, 0)))).andExpect(status().isCreated()), "screenId");

        mockMvc.perform(json(put("/api/admin/screens/{id}/layout", screenId).with(DALLAS_ADMIN),
                        Map.of("rows", rows(3, 4, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats", hasSize(12)))
                .andExpect(jsonPath("$.seats[*].rowLabel",
                        contains("A", "A", "A", "A", "B", "B", "B", "B", "C", "C", "C", "C")))
                .andExpect(jsonPath("$.seats[11].category").value("PREMIUM"));
        assertThat(jdbc.queryForList("SELECT row_label || seat_number FROM seat WHERE screen_id = ? ORDER BY id",
                String.class, screenId)).hasSize(12).doesNotContain("A5", "B5");

        // A seeded screen has sold seats; its plan never changes, and neither do its bookings' seats.
        long seatsBefore = jdbc.queryForObject("SELECT count(*) FROM seat WHERE screen_id = ?", Long.class,
                starlightScreen1);
        mockMvc.perform(json(put("/api/admin/screens/{id}/layout", starlightScreen1).with(DALLAS_ADMIN),
                        Map.of("rows", rows(2, 5, 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCREEN_HAS_SHOWTIMES"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat WHERE screen_id = ?", Long.class, starlightScreen1))
                .isEqualTo(seatsBefore);
    }

    @Test
    void invalidScreensAreRejectedWithTheOffendingFields() throws Exception {
        expectInvalid(Map.of(), "name", "rows");
        expectInvalid(newScreen("   ", rows(2, 5, 0)), "name");
        expectInvalid(newScreen("x".repeat(41), rows(2, 5, 0)), "name");
        expectInvalid(newScreen("Screen 3", List.of()), "rows");
        expectInvalid(newScreen("Screen 3", rows(27, 5, 0)), "rows");

        List<Map<String, Object>> bad = new ArrayList<>(List.of(
                row("a", 5, "STANDARD"), row("B", 0, "STANDARD"), row("C", 41, "STANDARD"),
                row("D", 5, "VIP"), row("B", 5, "PREMIUM"), row("AA", 5, "STANDARD")));
        expectInvalid(newScreen("Screen 3", bad), "rows[0].rowLabel", "rows[1].seatCount", "rows[2].seatCount",
                "rows[3].category", "rows[4].rowLabel", "rows[5].rowLabel");

        mockMvc.perform(post("/api/admin/theaters/{id}/screens", starlight).with(DALLAS_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":\"many\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(json(post("/api/admin/theaters/{id}/screens", starlight).with(DALLAS_ADMIN),
                        newScreen(" Screen 1 ", rows(2, 5, 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCREEN_NAME_TAKEN"))
                .andExpect(jsonPath("$.details.name").value("Screen 1"));
        // Names are unique per theater, not globally.
        mockMvc.perform(json(post("/api/admin/theaters/{id}/screens", bayou).with(HOUSTON_ADMIN),
                        newScreen("Screen 2", rows(2, 5, 0))))
                .andExpect(status().isCreated());

        assertThat(screenCount(starlight)).isEqualTo(2);
    }

    @Test
    void concurrentScreensWithTheSameNameProduceExactlyOne() throws Exception {
        AuthenticatedUser admin = users.loadUserByUsername("admin.dallas");
        NewScreen request = new NewScreen("Screen 3",
                List.of(new SeatLayout.Row("A", 10, "STANDARD"), new SeatLayout.Row("B", 10, "PREMIUM")));

        List<String> results = race(6, i -> () -> {
            adminTheaterService.createScreen(admin, starlight, request);
            return "CREATED";
        });

        assertThat(results).containsOnly("CREATED", "SCREEN_NAME_TAKEN");
        assertThat(results).filteredOn("CREATED"::equals).hasSize(1);
        assertThat(screenCount(starlight)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM seat se JOIN screen sc ON sc.id = se.screen_id
                WHERE sc.theater_id = ? AND sc.name = 'Screen 3'""", Long.class, starlight)).isEqualTo(20);
    }

    @Test
    void concurrentNonOverlappingShowtimesOnOneScreenAllSucceed() throws Exception {
        AuthenticatedUser admin = users.loadUserByUsername("admin.dallas");

        List<String> results = race(8, i -> () -> {
            adminShowtimeService.createShowtime(admin, new NewShowtime(coco, starlightScreen2, "en",
                    LocalDateTime.parse("2026-11-10T20:00").plusDays(i), new BigDecimal("12.50"),
                    new BigDecimal("16.00")));
            return "CREATED";
        });

        assertThat(results).containsOnly("CREATED");
        assertThat(fixtures.nonSeedShowtimeCount()).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM show_seat ss JOIN showtime st ON st.id = ss.showtime_id
                WHERE st.created_at = ?""", Long.class, OffsetDateTime.parse("2026-11-02T12:00:00Z")))
                .isEqualTo(8 * 40);
    }

    /**
     * Layout replacement and the first showtime race on a fresh screen. Whichever wins, the
     * showtime's inventory must match the screen's seats exactly: no inventory for removed seats and
     * no seats without inventory.
     */
    @Test
    void aLayoutChangeRacingTheFirstShowtimeNeverLeavesInventoryOutOfStep() throws Exception {
        AuthenticatedUser admin = users.loadUserByUsername("admin.dallas");
        List<String> replaceOutcomes = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            long screenId = adminTheaterService.createScreen(admin, starlight,
                    new NewScreen("Race " + round, List.of(new SeatLayout.Row("A", 10, "STANDARD")))).screenId();
            SeatLayout bigger = new SeatLayout(
                    List.of(new SeatLayout.Row("A", 6, "STANDARD"), new SeatLayout.Row("B", 6, "PREMIUM")));
            NewShowtime first = new NewShowtime(coco, screenId, "en", LocalDateTime.parse("2026-11-10T20:00"),
                    new BigDecimal("12.50"), new BigDecimal("16.00"));

            List<String> results = race(2, i -> i == 0
                    ? () -> {
                        adminTheaterService.replaceLayout(admin, screenId, bigger);
                        return "REPLACED";
                    }
                    : () -> {
                        adminShowtimeService.createShowtime(admin, first);
                        return "CREATED";
                    });

            assertThat(results.get(1)).isEqualTo("CREATED");
            assertThat(results.get(0)).isIn("REPLACED", "SCREEN_HAS_SHOWTIMES");
            replaceOutcomes.add(results.get(0));
            long seats = jdbc.queryForObject("SELECT count(*) FROM seat WHERE screen_id = ?", Long.class, screenId);
            assertThat(seats).isEqualTo("REPLACED".equals(results.get(0)) ? 12 : 10);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM show_seat ss JOIN showtime st ON st.id = ss.showtime_id
                    WHERE st.screen_id = ?""", Long.class, screenId)).isEqualTo(seats);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM seat se WHERE se.screen_id = ? AND NOT EXISTS (
                        SELECT 1 FROM show_seat ss WHERE ss.seat_id = se.id)""", Long.class, screenId)).isZero();
        }
        assertThat(replaceOutcomes).hasSize(5);
    }

    @Test
    void languagesArePublicAndSortedByName() throws Exception {
        mockMvc.perform(get("/api/languages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("English", "Hindi", "Japanese", "Korean", "Spanish")))
                .andExpect(jsonPath("$[0].code").value("en"));
    }

    private void expectInvalid(Object body, String... fields) throws Exception {
        mockMvc.perform(json(post("/api/admin/theaters/{id}/screens", starlight).with(DALLAS_ADMIN), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.fields", containsInAnyOrder(fields)));
    }

    /**
     * Runs the tasks on separate threads released together by a latch and returns each outcome in
     * task order: the task's own result, or the code of the ApiException it threw.
     */
    private static List<String> race(int count, IntFunction<Callable<String>> task)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<String> body = task.apply(i);
                outcomes.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return body.call();
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
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String hold(RequestPostProcessor customer, long showtimeId, List<Long> seatIds) throws Exception {
        return body(mockMvc.perform(json(post("/api/showtimes/{id}/holds", showtimeId).with(customer),
                Map.of("seatIds", seatIds))).andExpect(status().isCreated())).get("holdId").asText();
    }

    private ResultActions createShowtime(RequestPostProcessor admin, long screenId, String startsAtLocal)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("movieId", coco);
        body.put("screenId", screenId);
        body.put("languageCode", "en");
        body.put("startsAtLocal", startsAtLocal);
        body.put("standardPrice", new BigDecimal("11.00"));
        body.put("premiumPrice", new BigDecimal("15.50"));
        return mockMvc.perform(json(post("/api/admin/showtimes").with(admin), body));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private static Map<String, Object> newScreen(String name, List<Map<String, Object>> rows) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("rows", rows);
        return body;
    }

    /** {@code count} rows of {@code seats} seats from row A; the last {@code premium} rows are PREMIUM. */
    private static List<Map<String, Object>> rows(int count, int seats, int premium) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String label = i < 26 ? String.valueOf((char) ('A' + i)) : "A" + (char) ('A' + i - 26);
            rows.add(row(label, seats, i >= count - premium ? "PREMIUM" : "STANDARD"));
        }
        return rows;
    }

    private static Map<String, Object> row(String label, int seats, String category) {
        return Map.of("rowLabel", label, "seatCount", seats, "category", category);
    }

    private long screenCount(long theaterId) {
        return jdbc.queryForObject("SELECT count(*) FROM screen WHERE theater_id = ?", Long.class, theaterId);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private long createdId(ResultActions result, String field) throws Exception {
        return body(result).get(field).asLong();
    }
}
