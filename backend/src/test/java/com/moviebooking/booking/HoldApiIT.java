package com.moviebooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.MutableClock;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;

/** Seat-hold API against PostgreSQL with the seeded demo users. Requires Docker. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class HoldApiIT {

    private static final RequestPostProcessor ALICE = httpBasic("alice", "alice-demo-pass");
    private static final RequestPostProcessor BOB = httpBasic("bob", "bob-demo-pass");
    private static final RequestPostProcessor DALLAS_ADMIN = httpBasic("admin.dallas", "dallas-admin-demo");
    private static final Duration TTL = Duration.ofMinutes(8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HoldFixtures db;
    private long showtime;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        db = new HoldFixtures(jdbcTemplate);
        db.reset();
        clock.set(TestClockConfiguration.START);
        showtime = db.showtimes(1).getFirst();
        seats = db.seatIds(showtime, 12);
    }

    @AfterEach
    void tearDown() {
        db.reset();
    }

    @Test
    void holdsSeatsAndReturnsIdSortedSeatsAndClockBasedExpiry() throws Exception {
        ResultActions result = hold(ALICE, seats.get(1), seats.get(0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.showtimeId").value((int) showtime))
                .andExpect(jsonPath("$.seatIds", contains(seats.get(0).intValue(), seats.get(1).intValue())))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value(TestClockConfiguration.START.plus(TTL).toString()));

        UUID holdId = holdId(result);
        assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(db.seatsHeldBy(holdId)).containsExactly(seats.get(0), seats.get(1));
        assertThat(db.heldSeats(showtime)).hasSize(2);
    }

    @Test
    void seatMapShowsEffectiveStatusIncludingLazyExpiry() throws Exception {
        long seat = seats.get(0);
        seatMap(BOB).andExpect(seatStatus(seat, "AVAILABLE"));

        hold(ALICE, seat).andExpect(status().isCreated());
        seatMap(BOB).andExpect(seatStatus(seat, "HELD"));

        clock.advance(TTL);
        seatMap(BOB).andExpect(seatStatus(seat, "AVAILABLE"));
        assertThat(db.seatStatus(showtime, seat)).as("row is untouched until a write needs it").isEqualTo("HELD");
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"seatIds\":[]}", "{\"seatIds\":[1,2,3,4,5,6,7,8,9,10,11]}",
            "{\"seatIds\":[5,5]}", "{\"seatIds\":[5,null]}", "{\"seatIds\":\"5\"}", "not json" })
    void malformedSelectionsAreRejectedWithNothingHeld(String requestBody) throws Exception {
        mockMvc.perform(post("/api/showtimes/{id}/holds", showtime).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(db.holdCount()).isZero();
    }

    @Test
    void duplicateSeatIdsAreListed() throws Exception {
        hold(ALICE, seats.get(0), seats.get(1), seats.get(0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.duplicateSeatIds", contains(seats.get(0).intValue())));
    }

    @Test
    void unknownSeatsAndSeatsOfAnotherScreenAreRejectedWithNothingHeld() throws Exception {
        long foreignSeat = db.seatOfAnotherScreen(showtime);

        hold(ALICE, seats.get(0), foreignSeat, 999_999_999L)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEATS"))
                .andExpect(jsonPath("$.details.seatIds", contains((int) foreignSeat, 999_999_999)));

        assertThat(db.holdCount()).isZero();
        assertThat(db.heldSeats(showtime)).isEmpty();
    }

    @Test
    void unknownShowtimeIsNotFound() throws Exception {
        mockMvc.perform(post("/api/showtimes/{id}/holds", 999_999_999L).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/showtimes/{id}/seats", 999_999_999L).with(ALICE))
                .andExpect(status().isNotFound());
    }

    @Test
    void alreadyHeldSeatConflictsAndMultiSeatRequestHoldsNothing() throws Exception {
        UUID aliceHold = holdId(hold(ALICE, seats.get(1)).andExpect(status().isCreated()));

        hold(BOB, seats.get(0), seats.get(1), seats.get(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.seatIds", contains(seats.get(1).intValue())));

        assertThat(db.holdCountOf(db.userId("bob"))).isZero();
        assertThat(db.heldSeats(showtime)).containsOnlyKeys(seats.get(1)).containsValue(aliceHold);
        assertThat(db.seatStatus(showtime, seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(db.seatStatus(showtime, seats.get(2))).isEqualTo("AVAILABLE");
    }

    @Test
    void expiredHoldStopsBlockingExactlyAtExpiry() throws Exception {
        UUID aliceHold = holdId(hold(ALICE, seats.get(0), seats.get(1)).andExpect(status().isCreated()));

        clock.advance(TTL.minusSeconds(1));
        hold(BOB, seats.get(0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"));

        clock.advance(Duration.ofSeconds(1));
        UUID bobHold = holdId(hold(BOB, seats.get(0)).andExpect(status().isCreated()));

        assertThat(db.seatsHeldBy(bobHold)).containsExactly(seats.get(0));
        assertThat(db.seatsHeldBy(aliceHold)).containsExactly(seats.get(1));
        assertThat(db.holdStatus(aliceHold)).as("expiry is lazy; nobody touched Alice's hold row")
                .isEqualTo("ACTIVE");
    }

    @Test
    void customerWithExpiredHoldGetsItReplacedAndItsOtherSeatsFreed() throws Exception {
        UUID oldHold = holdId(hold(ALICE, seats.get(0), seats.get(1)).andExpect(status().isCreated()));
        clock.advance(TTL);

        UUID newHold = holdId(hold(ALICE, seats.get(1), seats.get(2)).andExpect(status().isCreated()));

        assertThat(db.holdStatus(oldHold)).isEqualTo("EXPIRED");
        assertThat(db.holdStatus(newHold)).isEqualTo("ACTIVE");
        assertThat(db.seatsHeldBy(newHold)).containsExactly(seats.get(1), seats.get(2));
        assertThat(db.seatStatus(showtime, seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(db.activeHoldCount()).isOne();
    }

    @Test
    void customerHasAtMostOneActiveHoldPerShowtime() throws Exception {
        UUID first = holdId(hold(ALICE, seats.get(0)).andExpect(status().isCreated()));

        hold(ALICE, seats.get(5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_HOLD_EXISTS"))
                .andExpect(jsonPath("$.details.holdId").value(first.toString()));
        assertThat(db.seatStatus(showtime, seats.get(5))).isEqualTo("AVAILABLE");

        release(ALICE, first).andExpect(status().isNoContent());
        hold(ALICE, seats.get(5)).andExpect(status().isCreated());

        long otherShowtime = db.showtimes(2).get(1);
        mockMvc.perform(post("/api/showtimes/{id}/holds", otherShowtime).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[" + db.seatIds(otherShowtime, 1).getFirst() + "]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void customerReleasesOwnHoldAndReleaseIsIdempotent() throws Exception {
        UUID holdId = holdId(hold(ALICE, seats.get(0), seats.get(1)).andExpect(status().isCreated()));

        release(ALICE, holdId).andExpect(status().isNoContent());

        assertThat(db.holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(db.heldSeats(showtime)).isEmpty();
        release(ALICE, holdId).andExpect(status().isNoContent());
        assertThat(db.holdStatus(holdId)).isEqualTo("RELEASED");
        hold(BOB, seats.get(0), seats.get(1)).andExpect(status().isCreated());
    }

    @Test
    void anotherCustomersHoldLooksNonexistentAndIsNotReleased() throws Exception {
        UUID holdId = holdId(hold(ALICE, seats.get(0)).andExpect(status().isCreated()));

        release(BOB, holdId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        release(BOB, UUID.randomUUID()).andExpect(status().isNotFound());

        assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(db.seatsHeldBy(holdId)).containsExactly(seats.get(0));
    }

    @Test
    void releasingAnExpiredHoldOnlyFreesSeatsItStillOwns() throws Exception {
        UUID aliceHold = holdId(hold(ALICE, seats.get(0), seats.get(1)).andExpect(status().isCreated()));
        clock.advance(TTL);
        UUID bobHold = holdId(hold(BOB, seats.get(0)).andExpect(status().isCreated()));

        release(ALICE, aliceHold).andExpect(status().isNoContent());

        assertThat(db.holdStatus(aliceHold)).isEqualTo("EXPIRED");
        assertThat(db.seatsHeldBy(bobHold)).containsExactly(seats.get(0));
        assertThat(db.seatStatus(showtime, seats.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    void startedOrCancelledShowtimeCannotBeHeld() throws Exception {
        clock.set(db.startsAt(showtime).minusSeconds(1));
        UUID holdId = holdId(hold(ALICE, seats.get(0)).andExpect(status().isCreated()));
        release(ALICE, holdId).andExpect(status().isNoContent());

        clock.set(db.startsAt(showtime));
        hold(ALICE, seats.get(0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOWTIME_CLOSED"));

        clock.set(TestClockConfiguration.START);
        db.cancel(showtime);
        hold(ALICE, seats.get(0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOWTIME_CLOSED"));
        assertThat(db.heldSeats(showtime)).isEmpty();
    }

    @Test
    void holdEndpointsRequireACustomer() throws Exception {
        mockMvc.perform(post("/api/showtimes/{id}/holds", showtime)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        hold(DALLAS_ADMIN, seats.get(0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        release(DALLAS_ADMIN, UUID.randomUUID()).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/showtimes/{id}/seats", showtime).with(DALLAS_ADMIN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/holds/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/holds/{id}", UUID.randomUUID()).with(DALLAS_ADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(db.holdCount()).isZero();
    }

    @Test
    void ownerCanReadAHoldWithoutExtendingIt() throws Exception {
        UUID holdId = holdId(hold(ALICE, seats.get(2), seats.get(0)).andExpect(status().isCreated()));
        Instant expiresAt = TestClockConfiguration.START.plus(TTL);

        mockMvc.perform(get("/api/holds/{id}", holdId).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId.toString()))
                .andExpect(jsonPath("$.showtimeId").value((int) showtime))
                .andExpect(jsonPath("$.seatIds", contains(seats.get(0).intValue(), seats.get(2).intValue())))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()))
                .andExpect(jsonPath("$.paymentId").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.scenario").doesNotExist());

        clock.advance(Duration.ofMinutes(1));
        mockMvc.perform(get("/api/holds/{id}", holdId).with(ALICE))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()));
        assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT expires_at FROM seat_hold WHERE id = ?", Instant.class, holdId))
                .isEqualTo(expiresAt);
    }

    @Test
    void expiredHoldIsReportedExpiredWithoutWriting() throws Exception {
        UUID holdId = holdId(hold(ALICE, seats.get(0)).andExpect(status().isCreated()));
        Instant expiresAt = TestClockConfiguration.START.plus(TTL);
        clock.advance(TTL);

        mockMvc.perform(get("/api/holds/{id}", holdId).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()))
                .andExpect(jsonPath("$.seatIds", contains(seats.get(0).intValue())));

        assertThat(db.holdStatus(holdId)).as("GET must not persist lazy expiry").isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT expires_at FROM seat_hold WHERE id = ?", Instant.class, holdId))
                .isEqualTo(expiresAt);
    }

    @Test
    void releasedAndConvertedHoldsReturnTheirStoredStatus() throws Exception {
        UUID released = holdId(hold(ALICE, seats.get(0)).andExpect(status().isCreated()));
        release(ALICE, released).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/holds/{id}", released).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.seatIds", hasSize(0)));

        UUID converted = holdId(hold(ALICE, seats.get(1)).andExpect(status().isCreated()));
        mockMvc.perform(post("/api/holds/{id}/payment", converted).with(ALICE)
                        .header("Idempotency-Key", "hold-lookup-converted")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"SUCCESS\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/holds/{id}", converted).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONVERTED"))
                .andExpect(jsonPath("$.seatIds", contains(seats.get(1).intValue())))
                .andExpect(jsonPath("$.paymentId").doesNotExist());
        assertThat(db.holdStatus(converted)).isEqualTo("CONVERTED");
    }

    @Test
    void anotherCustomersHoldLooksNonexistentOnLookup() throws Exception {
        UUID holdId = holdId(hold(ALICE, seats.get(0)).andExpect(status().isCreated()));

        mockMvc.perform(get("/api/holds/{id}", holdId).with(BOB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/holds/{id}", UUID.randomUUID()).with(ALICE))
                .andExpect(status().isNotFound());
        assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    @Test
    void tenSeatsIsTheMaximumThatCanBeHeld() throws Exception {
        Long[] ten = LongStream.range(0, 10).mapToObj(i -> seats.get((int) i)).toArray(Long[]::new);

        UUID holdId = holdId(hold(ALICE, ten).andExpect(status().isCreated()));

        assertThat(db.seatsHeldBy(holdId)).hasSize(10);
    }

    private ResultActions hold(RequestPostProcessor user, Long... seatIds) throws Exception {
        return mockMvc.perform(post("/api/showtimes/{id}/holds", showtime).with(user)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new HoldController.CreateHoldRequest(List.of(seatIds)))));
    }

    private ResultActions release(RequestPostProcessor user, UUID holdId) throws Exception {
        return mockMvc.perform(delete("/api/holds/{id}", holdId).with(user));
    }

    private ResultActions seatMap(RequestPostProcessor user) throws Exception {
        return mockMvc.perform(get("/api/showtimes/{id}/seats", showtime).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showtimeId").value((int) showtime));
    }

    private static ResultMatcher seatStatus(long seatId, String expected) {
        return jsonPath("$.seats[?(@.seatId == " + seatId + ")].status", contains(expected));
    }

    private UUID holdId(ResultActions result) throws Exception {
        return UUID.fromString(json(result.andReturn().getResponse().getContentAsString()).get("holdId").asText());
    }

    private JsonNode json(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
