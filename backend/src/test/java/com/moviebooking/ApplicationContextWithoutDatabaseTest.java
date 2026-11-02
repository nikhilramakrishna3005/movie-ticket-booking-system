package com.moviebooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.moviebooking.booking.BookingRepository;
import com.moviebooking.booking.BookingSeatRepository;
import com.moviebooking.booking.OutboxEventRepository;
import com.moviebooking.booking.SeatHoldRepository;
import com.moviebooking.booking.ShowSeatRepository;
import com.moviebooking.config.ResourceNotFoundException;
import com.moviebooking.catalog.LanguageRepository;
import com.moviebooking.catalog.MovieRepository;
import com.moviebooking.catalog.ShowtimeSearch;
import com.moviebooking.payment.PaymentAttemptRepository;
import com.moviebooking.payment.SimulatedPaymentProvider;
import com.moviebooking.theater.CityRepository;
import com.moviebooking.theater.ShowtimeScheduler;
import com.moviebooking.security.AppUser;
import com.moviebooking.security.AppUserRepository;
import com.moviebooking.security.Role;
import com.moviebooking.theater.SeatRepository;
import com.moviebooking.theater.ShowtimeRepository;

/**
 * Loads the application context with the real security configuration but without database
 * auto-configuration, so it runs where Docker is unavailable. Repositories are mocked; the same
 * flows against PostgreSQL are covered by {@code SecurityIT} and {@code HoldApiIT}.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@MockitoBean(types = { BookingRepository.class, BookingSeatRepository.class, OutboxEventRepository.class,
        MovieRepository.class })
class ApplicationContextWithoutDatabaseTest {

    private static final String PASSWORD = "test-only-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private AppUserRepository users;

    @MockitoBean
    private ShowtimeRepository showtimes;

    @MockitoBean
    private CityRepository cities;

    @MockitoBean
    private LanguageRepository languages;

    // JdbcTemplate-backed; replaced so this context needs no DataSource.
    @MockitoBean
    private ShowtimeSearch showtimeSearch;

    @MockitoBean
    private ShowtimeScheduler showtimeScheduler;

    @MockitoBean
    private SeatRepository seats;

    @MockitoBean
    private ShowSeatRepository showSeats;

    @MockitoBean
    private SeatHoldRepository holds;

    @MockitoBean
    private PaymentAttemptRepository paymentAttempts;

    // Replaced as a whole: the real provider needs a transaction manager, absent in this context.
    @MockitoBean
    private SimulatedPaymentProvider provider;

    @BeforeEach
    void stubUsers() {
        AppUser customer = mock(AppUser.class);
        given(customer.getId()).willReturn(41L);
        given(customer.getUsername()).willReturn("carol");
        given(customer.getPasswordHash()).willReturn(passwordEncoder.encode(PASSWORD));
        given(customer.getRole()).willReturn(Role.CUSTOMER);
        given(customer.getDisplayName()).willReturn("Carol");
        given(users.findByUsername("carol")).willReturn(Optional.of(customer));

        AppUser admin = mock(AppUser.class);
        given(admin.getId()).willReturn(42L);
        given(admin.getUsername()).willReturn("dana");
        given(admin.getPasswordHash()).willReturn(passwordEncoder.encode(PASSWORD));
        given(admin.getRole()).willReturn(Role.THEATER_ADMIN);
        given(admin.getDisplayName()).willReturn("Dana");
        given(users.findByUsername("dana")).willReturn(Optional.of(admin));
        given(users.findAssignedTheaterIds(42L)).willReturn(List.of(7L));
    }

    @Test
    void clockBeanIsUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void healthEndpointIsPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void missingCredentialsAreRejectedWithBasicChallenge() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"movie-booking\""))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void ajaxRequestsGetNoBasicChallengeSoBrowsersShowNoNativeDialog() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(httpBasic("carol", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownUserIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(httpBasic("nobody", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void validCustomerGetsProfileWithoutPasswordData() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me").with(httpBasic("carol", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41))
                .andExpect(jsonPath("$.username").value("carol"))
                .andExpect(jsonPath("$.displayName").value("Carol"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("password").doesNotContain("$2a$").doesNotContain(PASSWORD);
    }

    @Test
    void theaterAdminRoleIsIdentified() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(httpBasic("dana", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("THEATER_ADMIN"));
    }

    @Test
    void customerIsForbiddenFromAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/theaters/7/showtimes").with(httpBasic("carol", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void nonApiEndpointsStayDenied() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/info").with(httpBasic("carol", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossOriginRequestsAreNotGrantedCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .with(httpBasic("carol", PASSWORD))
                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"seatIds\":[]}", "{\"seatIds\":[1,2,3,4,5,6,7,8,9,10,11]}",
            "{\"seatIds\":[4,7,4]}", "{\"seatIds\":[4,null]}" })
    void invalidSeatSelectionsAreRejectedBeforeAnyDatabaseAccess(String body) throws Exception {
        mockMvc.perform(post("/api/showtimes/1/holds").with(httpBasic("carol", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(showtimes, seats, showSeats, holds);
    }

    @Test
    void holdEndpointsAreForCustomersOnly() throws Exception {
        mockMvc.perform(post("/api/showtimes/1/holds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/showtimes/1/holds").with(httpBasic("dana", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/holds/{id}", UUID.randomUUID()).with(httpBasic("dana", PASSWORD)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(showtimes, seats, showSeats, holds);
    }

    @Test
    void paymentAndBookingEndpointsAreForCustomersOnly() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/holds/{id}/payment", id).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"SUCCESS\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/holds/{id}/payment", id).with(httpBasic("dana", PASSWORD))
                        .header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"SUCCESS\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payments/{id}", id).with(httpBasic("dana", PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/bookings/{id}", id).with(httpBasic("dana", PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/bookings/{id}", id))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(holds, paymentAttempts, provider);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "missing", "too-long" })
    void invalidIdempotencyKeysAreRejectedBeforeAnyDatabaseAccess(String key) throws Exception {
        var request = post("/api/holds/{id}/payment", UUID.randomUUID()).with(httpBasic("carol", PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"SUCCESS\"}");
        if (!key.equals("missing")) {
            request.header("Idempotency-Key", key.equals("too-long") ? "k".repeat(101) : key);
        }
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(holds, paymentAttempts, provider);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"scenario\":\"STEAL\"}", "not json" })
    void invalidPaymentBodiesAreRejectedBeforeAnyDatabaseAccess(String body) throws Exception {
        mockMvc.perform(post("/api/holds/{id}/payment", UUID.randomUUID()).with(httpBasic("carol", PASSWORD))
                        .header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(holds, paymentAttempts, provider);
    }

    @Test
    void anonymousVisitorsCanBrowseButCannotBook() throws Exception {
        given(showtimeSearch.search(null, null, null, null, null, null, null))
                .willReturn(new ShowtimeSearch.ShowtimePage(java.time.LocalDate.parse("2026-11-02"),
                        "America/Chicago", 0, 20, 0, 0, List.of()));
        given(showtimeSearch.get(1L)).willThrow(new ResourceNotFoundException());

        mockMvc.perform(get("/api/cities")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/movies")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/showtimes")).andExpect(status().isOk()).andExpect(jsonPath("$.results").isArray());
        mockMvc.perform(get("/api/showtimes/1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/showtimes/1/seats")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/showtimes/1/holds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/showtimes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(showtimeScheduler);
    }

    @Test
    void customersCannotCreateShowtimes() throws Exception {
        mockMvc.perform(post("/api/admin/showtimes").with(httpBasic("carol", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(showtimeScheduler);
    }

    @Test
    void unknownApiPathsUseTheErrorFormat() throws Exception {
        mockMvc.perform(get("/api/does-not-exist").with(httpBasic("carol", PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
