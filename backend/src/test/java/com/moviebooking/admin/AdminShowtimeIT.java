package com.moviebooking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.MutableClock;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;
import com.moviebooking.booking.HoldFixtures;

/** Theater-admin showtime creation against PostgreSQL. Requires Docker. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class AdminShowtimeIT {

    private static final RequestPostProcessor DALLAS = httpBasic("admin.dallas", "dallas-admin-demo");
    private static final RequestPostProcessor HOUSTON = httpBasic("admin.houston", "houston-admin-demo");
    private static final RequestPostProcessor ALICE = httpBasic("alice", "alice-demo-pass");
    private static final LocalDateTime START = LocalDateTime.parse("2027-02-01T13:00:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private HoldFixtures db;
    private long starlightScreen;
    private long interstellar;

    @BeforeEach
    void setUp() {
        db = new HoldFixtures(jdbc);
        db.reset();
        clock.set(TestClockConfiguration.START);
        starlightScreen = screenId("Starlight Cinemas Downtown", "Screen 1");
        interstellar = jdbc.queryForObject("SELECT id FROM movie WHERE title = 'Interstellar'", Long.class);
    }

    @AfterEach
    void tearDown() {
        db.reset();
    }

    @Test
    void assignedAdminCreatesAShowtimeAndItsSeatInventory() throws Exception {
        int seatsOnScreen = jdbc.queryForObject("SELECT count(*) FROM seat WHERE screen_id = ?", Integer.class,
                starlightScreen);

        String body = create(DALLAS, starlightScreen, interstellar, "en", START, "13.00", "19.50")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.screenId").value(starlightScreen))
                .andExpect(jsonPath("$.languageCode").value("en"))
                .andExpect(jsonPath("$.localDate").value("2027-02-01"))
                .andExpect(jsonPath("$.localTime").value("13:00:00"))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.startsAt").value("2027-02-01T19:00:00Z"))
                .andExpect(jsonPath("$.seatCount").value(seatsOnScreen))
                .andReturn().getResponse().getContentAsString();
        long showtimeId = json(body).get("showtimeId").asLong();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM show_seat WHERE showtime_id = ?", Integer.class,
                showtimeId)).isEqualTo(seatsOnScreen);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM show_seat ss JOIN seat se ON se.id = ss.seat_id
                WHERE ss.showtime_id = ? AND se.category = 'STANDARD' AND ss.price = 13.00 AND ss.status = 'AVAILABLE'""",
                Integer.class, showtimeId)).isPositive();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM show_seat ss JOIN seat se ON se.id = ss.seat_id
                WHERE ss.showtime_id = ? AND se.category = 'PREMIUM' AND ss.price = 19.50""",
                Integer.class, showtimeId)).isPositive();
        // 169 minutes plus the 15 minute turnover.
        assertThat(json(body).get("endsAt").asText()).isEqualTo("2027-02-01T22:04:00Z");

        mockMvc.perform(get("/api/showtimes/{id}", showtimeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movie.title").value("Interstellar"))
                .andExpect(jsonPath("$.seats[?(@.category == 'STANDARD')].price",
                        org.hamcrest.Matchers.contains(13.0)))
                .andExpect(jsonPath("$.seats[?(@.category == 'PREMIUM')].available").exists());
    }

    @Test
    void anAdminCannotCreateAShowtimeForAnUnassignedTheater() throws Exception {
        long before = showtimeCount();

        create(HOUSTON, starlightScreen, interstellar, "en", START, "13.00", "19.50")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        create(ALICE, starlightScreen, interstellar, "en", START, "13.00", "19.50")
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/showtimes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(showtimeCount()).isEqualTo(before);
        assertThat(mockMvc.perform(post("/api/admin/showtimes").with(HOUSTON)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString()).doesNotContain("password").doesNotContain("assigned");
    }

    @Test
    void overlappingShowtimesAreRejectedAndBackToBackShowtimesAreNot() throws Exception {
        create(DALLAS, starlightScreen, interstellar, "en", START, "13.00", "19.50").andExpect(status().isCreated());
        long afterFirst = showtimeCount();

        create(DALLAS, starlightScreen, interstellar, "en", START.plusHours(1), "13.00", "19.50")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOWTIME_OVERLAP"));
        assertThat(showtimeCount()).as("the rejected insert rolled back").isEqualTo(afterFirst);

        // Interstellar runs 169 minutes plus 15 minutes of turnover, ending at 16:04.
        create(DALLAS, starlightScreen, interstellar, "en", LocalDateTime.parse("2027-02-01T16:04:00"), "13.00",
                "19.50")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.localTime").value("16:04:00"));
        assertThat(showtimeCount()).isEqualTo(afterFirst + 1);
    }

    @Test
    void invalidCreateRequestsChangeNothing() throws Exception {
        long before = showtimeCount();

        create(DALLAS, starlightScreen, interstellar, "en", START, "0.00", "19.50")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        create(DALLAS, starlightScreen, interstellar, "en", START, "12.555", "19.50")
                .andExpect(status().isBadRequest());
        create(DALLAS, starlightScreen, interstellar, "zz", START, "13.00", "19.50")
                .andExpect(status().isBadRequest());
        create(DALLAS, starlightScreen, 999_999_999L, "en", START, "13.00", "19.50")
                .andExpect(status().isNotFound());
        create(DALLAS, 999_999_999L, interstellar, "en", START, "13.00", "19.50")
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/showtimes").with(DALLAS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"screenId\":" + starlightScreen + "}"))
                .andExpect(status().isBadRequest());

        assertThat(showtimeCount()).isEqualTo(before);
    }

    private ResultActions create(RequestPostProcessor user, long screenId, long movieId, String language,
            LocalDateTime startsAt, String standardPrice, String premiumPrice) throws Exception {
        String body = """
                {"screenId":%d,"movieId":%d,"languageCode":"%s","startsAt":"%s","standardPrice":%s,"premiumPrice":%s}"""
                .formatted(screenId, movieId, language, startsAt, standardPrice, premiumPrice);
        return mockMvc.perform(post("/api/admin/showtimes").with(user)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long screenId(String theater, String screen) {
        return jdbc.queryForObject("""
                SELECT sc.id FROM screen sc JOIN theater t ON t.id = sc.theater_id
                WHERE t.name = ? AND sc.name = ?""", Long.class, theater, screen);
    }

    private long showtimeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM showtime", Long.class);
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
