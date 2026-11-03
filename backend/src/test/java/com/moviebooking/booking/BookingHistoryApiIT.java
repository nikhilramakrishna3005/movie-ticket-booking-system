package com.moviebooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.MutableClock;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;

/**
 * GET /api/bookings against PostgreSQL with the seeded demo users. Bookings are created through
 * the real hold and payment endpoints; the test clock sets each booking's created_at, which is
 * the history's sort key. Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class BookingHistoryApiIT {

    private static final RequestPostProcessor ALICE = httpBasic("alice", "alice-demo-pass");
    private static final RequestPostProcessor BOB = httpBasic("bob", "bob-demo-pass");
    private static final RequestPostProcessor DALLAS_ADMIN = httpBasic("admin.dallas", "dallas-admin-demo");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HoldFixtures db;
    private List<Long> showtimes;

    @BeforeEach
    void setUp() {
        db = new HoldFixtures(jdbcTemplate);
        db.reset();
        clock.set(TestClockConfiguration.START);
        showtimes = db.showtimes(3);
    }

    @AfterEach
    void tearDown() {
        db.reset();
    }

    @Test
    void historyRequiresCredentials() throws Exception {
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/bookings").with(httpBasic("alice", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theaterAdminsHaveNoBookingHistory() throws Exception {
        mockMvc.perform(get("/api/bookings").with(DALLAS_ADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void aCustomerWithoutBookingsGetsAnEmptyPage() throws Exception {
        mockMvc.perform(get("/api/bookings").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(BookingPageRequest.DEFAULT_PAGE_SIZE))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void aBookingIsListedWithItsReferenceVenueLocalShowtimeSeatsAndAmount() throws Exception {
        List<Long> seats = db.seatIds(showtimes.get(0), 2);
        JsonNode booking = book(ALICE, showtimes.get(0), seats.get(1), seats.get(0));

        JsonNode item = history(ALICE, "").get("items").get(0);

        long showtimeId = showtimes.get(0);
        ZonedDateTime localStart = db.startsAt(showtimeId).atZone(zoneOf(showtimeId));
        assertThat(item.get("bookingId").asText()).isEqualTo(booking.get("bookingId").asText());
        assertThat(item.get("reference").asText()).isEqualTo(booking.get("reference").asText());
        assertThat(item.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(item.get("showtimeId").asLong()).isEqualTo(showtimeId);
        assertThat(item.get("movieTitle").asText()).isEqualTo(booking.get("movieTitle").asText()).isNotBlank();
        assertThat(item.get("theaterName").asText()).isEqualTo(booking.get("theaterName").asText()).isNotBlank();
        assertThat(item.get("screenName").asText()).isEqualTo(booking.get("screenName").asText());
        assertThat(item.get("city").asText()).isEqualTo(booking.get("city").asText());
        assertThat(item.get("startsAt").asText()).isEqualTo(db.startsAt(showtimeId).toString());
        assertThat(item.get("localDate").asText()).isEqualTo(localStart.toLocalDate().toString());
        assertThat(LocalTime.parse(item.get("localStartTime").asText())).isEqualTo(localStart.toLocalTime());
        assertThat(item.get("timeZone").asText()).isEqualTo(zoneOf(showtimeId).getId());
        assertThat(item.get("seats").findValuesAsText("seatId")).as("seats in seat-id order")
                .containsExactly(seats.get(0).toString(), seats.get(1).toString());
        assertThat(item.get("seats").get(0).get("rowLabel").asText()).isNotBlank();
        assertThat(new BigDecimal(item.get("totalAmount").asText()))
                .isEqualByComparingTo(new BigDecimal(booking.get("totalAmount").asText()));
        assertThat(item.get("confirmedAt").asText()).isEqualTo(TestClockConfiguration.START.toString());
    }

    @Test
    void customersOnlyEverSeeTheirOwnBookings() throws Exception {
        List<Long> seats = db.seatIds(showtimes.get(0), 3);
        String alicesFirst = book(ALICE, showtimes.get(0), seats.get(0)).get("bookingId").asText();
        String bobs = book(BOB, showtimes.get(0), seats.get(1)).get("bookingId").asText();
        String alicesSecond = book(ALICE, showtimes.get(1), db.seatIds(showtimes.get(1), 1).getFirst())
                .get("bookingId").asText();

        assertThat(bookingIds(history(ALICE, ""))).containsExactlyInAnyOrder(alicesFirst, alicesSecond);
        assertThat(bookingIds(history(BOB, ""))).containsExactly(bobs);

        // A customer id in the query string is not a parameter of this endpoint and changes nothing.
        long bobId = db.userId("bob");
        assertThat(bookingIds(history(ALICE, "?customerId=" + bobId)))
                .containsExactlyInAnyOrder(alicesFirst, alicesSecond);
        assertThat(history(ALICE, "?customerId=" + bobId).get("totalItems").asLong()).isEqualTo(2);

        mockMvc.perform(get("/api/bookings/{id}", bobs).with(ALICE)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bookings/{id}", alicesFirst).with(ALICE)).andExpect(status().isOk());
    }

    @Test
    void historyIsNewestFirstAndPagesWithoutGapsOrRepeats() throws Exception {
        List<String> bookedInOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long showtimeId = showtimes.get(i % showtimes.size());
            long seatId = db.seatIds(showtimeId, 2).get(i / showtimes.size());
            bookedInOrder.add(book(ALICE, showtimeId, seatId).get("bookingId").asText());
            clock.advance(Duration.ofMinutes(1));
        }
        List<String> newestFirst = bookedInOrder.reversed();

        JsonNode first = history(ALICE, "?page=0&size=2");
        JsonNode second = history(ALICE, "?page=1&size=2");
        JsonNode third = history(ALICE, "?page=2&size=2");
        JsonNode beyond = history(ALICE, "?page=3&size=2");

        assertThat(first.get("totalItems").asLong()).isEqualTo(5);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(bookingIds(first)).containsExactlyElementsOf(newestFirst.subList(0, 2));
        assertThat(bookingIds(second)).containsExactlyElementsOf(newestFirst.subList(2, 4));
        assertThat(bookingIds(third)).containsExactlyElementsOf(newestFirst.subList(4, 5));
        assertThat(bookingIds(beyond)).isEmpty();
        assertThat(beyond.get("totalItems").asLong()).isEqualTo(5);
        assertThat(beyond.get("page").asInt()).isEqualTo(3);

        List<Instant> confirmedAt = new ArrayList<>();
        history(ALICE, "").get("items").forEach(item -> confirmedAt.add(Instant.parse(item.get("confirmedAt").asText())));
        assertThat(confirmedAt).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void bookingsConfirmedAtTheSameInstantAreOrderedByIdSoPagesStayStable() throws Exception {
        List<Long> seats = db.seatIds(showtimes.get(0), 1);
        List<String> ids = new ArrayList<>();
        ids.add(book(ALICE, showtimes.get(0), seats.getFirst()).get("bookingId").asText());
        ids.add(book(ALICE, showtimes.get(1), db.seatIds(showtimes.get(1), 1).getFirst()).get("bookingId").asText());
        ids.add(book(ALICE, showtimes.get(2), db.seatIds(showtimes.get(2), 1).getFirst()).get("bookingId").asText());
        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            paged.addAll(bookingIds(history(ALICE, "?size=1&page=" + page)));
        }

        // PostgreSQL orders uuid by unsigned bytes, which is the order of the lower-case hex strings
        // (java.util.UUID.compareTo is signed, so it is not used here).
        assertThat(paged).containsExactlyElementsOf(ids.stream().sorted((a, b) -> b.compareTo(a)).toList());
    }

    @Test
    void declinedAndUnpaidHoldsAreNotBookings() throws Exception {
        List<Long> seats = db.seatIds(showtimes.get(0), 2);
        UUID declined = hold(ALICE, showtimes.get(0), seats.get(0));
        pay(ALICE, declined, "history-decline", "DECLINE");
        hold(ALICE, showtimes.get(1), db.seatIds(showtimes.get(1), 1).getFirst());

        assertThat(history(ALICE, "").get("totalItems").asLong()).isZero();
    }

    @Test
    void pageParametersAreBounded() throws Exception {
        mockMvc.perform(get("/api/bookings?size=0").with(ALICE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.parameter").value("size"));
        mockMvc.perform(get("/api/bookings?page=-1").with(ALICE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.parameter").value("page"));
        mockMvc.perform(get("/api/bookings?page=" + (BookingPageRequest.MAX_PAGE + 1)).with(ALICE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.parameter").value("page"));
        mockMvc.perform(get("/api/bookings?page=abc").with(ALICE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/bookings?size=500").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(BookingPageRequest.MAX_PAGE_SIZE));
    }

    private JsonNode book(RequestPostProcessor user, long showtimeId, Long... seatIds) throws Exception {
        UUID holdId = hold(user, showtimeId, seatIds);
        String body = pay(user, holdId, "history-" + UUID.randomUUID(), "SUCCESS");
        return objectMapper.readTree(body).get("booking");
    }

    private UUID hold(RequestPostProcessor user, long showtimeId, Long... seatIds) throws Exception {
        String body = mockMvc.perform(post("/api/showtimes/{id}/holds", showtimeId).with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldController.CreateHoldRequest(List.of(seatIds)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("holdId").asText());
    }

    private String pay(RequestPostProcessor user, UUID holdId, String key, String scenario) throws Exception {
        return mockMvc.perform(post("/api/holds/{id}/payment", holdId).with(user)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"" + scenario + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode history(RequestPostProcessor user, String query) throws Exception {
        String body = mockMvc.perform(get("/api/bookings" + query).with(user))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<String> bookingIds(JsonNode page) {
        return page.get("items").findValuesAsText("bookingId");
    }

    private ZoneId zoneOf(long showtimeId) {
        return ZoneId.of(jdbcTemplate.queryForObject("""
                SELECT c.time_zone FROM showtime st
                JOIN screen sc ON sc.id = st.screen_id JOIN theater t ON t.id = sc.theater_id
                JOIN city c ON c.id = t.city_id WHERE st.id = ?""", String.class, showtimeId));
    }
}
