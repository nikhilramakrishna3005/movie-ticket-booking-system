package com.moviebooking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.moviebooking.MutableClock;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;
import com.moviebooking.booking.HoldFixtures;
import com.moviebooking.booking.HoldService;
import com.moviebooking.booking.HoldService.HoldResult;
import com.moviebooking.booking.CheckoutService;
import com.moviebooking.booking.CheckoutService.PaymentOutcome;
import com.moviebooking.config.ApiException;
import com.moviebooking.payment.PaymentScenario;
import com.moviebooking.payment.PaymentStatus;
import com.moviebooking.payment.SimulatedPaymentProvider;
import com.moviebooking.security.AppUserDetailsService;
import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.CancelledShowtime;
import com.moviebooking.theater.ShowtimeStatus;

/**
 * Showtime cancellation against PostgreSQL. Assertions check stored rows, not only HTTP status.
 * Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class ShowtimeCancellationIT {

    private static final RequestPostProcessor DALLAS_ADMIN = httpBasic("admin.dallas", "dallas-admin-demo");
    private static final RequestPostProcessor HOUSTON_ADMIN = httpBasic("admin.houston", "houston-admin-demo");
    private static final RequestPostProcessor ALICE = httpBasic("alice", "alice-demo-pass");
    private static final String STARLIGHT = "Starlight Cinemas Downtown";
    private static final String BAYOU = "Bayou Picture House";
    private static final Duration TTL = Duration.ofMinutes(8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HoldService holdService;

    @Autowired
    private AdminShowtimeService adminShowtimeService;

    @Autowired
    private AppUserDetailsService users;

    @Autowired
    private CheckoutService checkoutService;

    @MockitoSpyBean
    private SimulatedPaymentProvider provider;

    private HoldFixtures db;
    private long starlightShow;
    private long bayouShow;
    private List<Long> seats;
    private AuthenticatedUser dallasAdmin;
    private AuthenticatedUser alice;

    @BeforeEach
    void setUp() {
        db = new HoldFixtures(jdbc);
        db.reset();
        clock.set(TestClockConfiguration.START);
        starlightShow = db.showtimeAt(STARLIGHT);
        bayouShow = db.showtimeAt(BAYOU);
        seats = db.seatIds(starlightShow, 4);
        dallasAdmin = users.loadUserByUsername("admin.dallas");
        alice = db.principal("alice");
    }

    @AfterEach
    void tearDown() {
        db.reset();
    }

    @Test
    void cancelRequiresAnAssignedTheaterAdmin() throws Exception {
        cancel(starlightShow, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        cancel(starlightShow, ALICE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        cancel(starlightShow, HOUSTON_ADMIN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        cancel(bayouShow, DALLAS_ADMIN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        cancel(9_999_999L, DALLAS_ADMIN)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(db.showtimeStatus(starlightShow)).isEqualTo("SCHEDULED");
        assertThat(db.showtimeStatus(bayouShow)).isEqualTo("SCHEDULED");
    }

    @Test
    void emptyShowtimeIsCancelledWithoutDeletingInventory() throws Exception {
        long inventory = db.seatInventory(starlightShow);

        cancel(starlightShow, DALLAS_ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showtimeId").value((int) starlightShow))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.theaterId").value((int) theaterId(STARLIGHT)))
                .andExpect(jsonPath("$.screenId").isNumber());

        assertThat(db.showtimeStatus(starlightShow)).isEqualTo("CANCELLED");
        assertThat(db.seatInventory(starlightShow)).isEqualTo(inventory);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM show_seat WHERE showtime_id = ? AND status = 'AVAILABLE'",
                Long.class, starlightShow)).isEqualTo(inventory);

        holdServiceCreateClosed();
    }

    @Test
    void repeatingCancelIsIdempotent() throws Exception {
        cancel(starlightShow, DALLAS_ADMIN).andExpect(status().isOk());
        cancel(starlightShow, DALLAS_ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(db.showtimeStatus(starlightShow)).isEqualTo("CANCELLED");
        assertThat(db.count("booking")).isZero();
    }

    @Test
    void expiredHoldIsSweptAndDoesNotBlockCancellation() throws Exception {
        UUID holdId = holdService.createHold(alice, starlightShow, List.of(seats.get(0))).holdId();
        clock.advance(TTL);

        cancel(starlightShow, DALLAS_ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(db.holdStatus(holdId)).isEqualTo("EXPIRED");
        assertThat(db.seatStatus(starlightShow, seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(db.showtimeStatus(starlightShow)).isEqualTo("CANCELLED");
    }

    @Test
    void activeHoldBlocksCancellationAndLeavesTheHold() throws Exception {
        UUID holdId = holdService.createHold(alice, starlightShow, List.of(seats.get(0), seats.get(1))).holdId();

        cancel(starlightShow, DALLAS_ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOWTIME_HAS_ACTIVE_HOLDS"))
                .andExpect(jsonPath("$.details.activeHolds").value(1));

        assertThat(db.showtimeStatus(starlightShow)).isEqualTo("SCHEDULED");
        assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(db.seatsHeldBy(holdId)).containsExactly(seats.get(0), seats.get(1));
    }

    @Test
    void confirmedBookingBlocksCancellationAndIsNotDeleted() throws Exception {
        UUID holdId = holdService.createHold(alice, starlightShow, List.of(seats.get(0))).holdId();
        PaymentOutcome booked = checkoutService.pay(alice, holdId, "cancel-booked", PaymentScenario.SUCCESS);
        UUID bookingId = booked.booking().bookingId();

        cancel(starlightShow, DALLAS_ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOWTIME_HAS_BOOKINGS"))
                .andExpect(jsonPath("$.details.bookedSeats").value(1));

        assertThat(db.showtimeStatus(starlightShow)).isEqualTo("SCHEDULED");
        assertThat(db.holdStatus(holdId)).isEqualTo("CONVERTED");
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(db.seatStatus(starlightShow, seats.get(0))).isEqualTo("BOOKED");
        assertThat(db.count("booking")).isOne();
        verify(provider, never()).voidCharge(anyString());
    }

    @Test
    void unresolvedPaymentBlocksCancellationWithoutVoiding() throws Exception {
        CountDownLatch charging = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            charging.countDown();
            assertThat(proceed.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(provider).charge(anyString(), any(), any());

        UUID holdId = holdService.createHold(alice, starlightShow, List.of(seats.get(0))).holdId();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentOutcome> inFlight = executor.submit(
                    () -> checkoutService.pay(alice, holdId, "cancel-inflight", PaymentScenario.SUCCESS));
            assertThat(charging.await(30, TimeUnit.SECONDS)).isTrue();

            cancel(starlightShow, DALLAS_ADMIN)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SHOWTIME_HAS_UNRESOLVED_PAYMENTS"));
            assertThat(db.showtimeStatus(starlightShow)).isEqualTo("SCHEDULED");
            assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM payment_attempt WHERE idempotency_key = 'cancel-inflight'",
                    String.class)).isEqualTo("PENDING");

            proceed.countDown();
            assertThat(inFlight.get(30, TimeUnit.SECONDS).payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
        assertThat(db.count("booking")).isOne();
        assertThat(db.showtimeStatus(starlightShow)).isEqualTo("SCHEDULED");
        verify(provider, never()).voidCharge(anyString());
    }

    @Test
    void concurrentCancelsLeaveTheShowCancelledOnce() throws Exception {
        List<Outcome<CancelledShowtime>> outcomes = runTogether(List.of(
                () -> adminShowtimeService.cancelShowtime(dallasAdmin, starlightShow),
                () -> adminShowtimeService.cancelShowtime(dallasAdmin, starlightShow)));

        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.error()).isNull();
            assertThat(outcome.result().status()).isEqualTo(ShowtimeStatus.CANCELLED);
        });
        assertThat(db.showtimeStatus(starlightShow)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM showtime WHERE id = ?", Long.class, starlightShow))
                .isOne();
    }

    @Test
    void cancelRacingAHoldLeavesEitherAHoldOnAScheduledShowOrACancelledShowWithNoHold() throws Exception {
        List<Outcome<Object>> outcomes = runTogether(List.of(
                () -> adminShowtimeService.cancelShowtime(dallasAdmin, starlightShow),
                () -> holdService.createHold(alice, starlightShow, List.of(seats.get(0)))));

        Outcome<Object> cancel = outcomes.get(0);
        Outcome<Object> hold = outcomes.get(1);
        String status = db.showtimeStatus(starlightShow);
        long activeHolds = db.activeHoldCount();

        if (cancel.result() != null) {
            assertThat(status).isEqualTo("CANCELLED");
            assertThat(activeHolds).isZero();
            assertThat(hold.error()).isInstanceOfSatisfying(ApiException.class,
                    api -> assertThat(api.code()).isEqualTo("SHOWTIME_CLOSED"));
        } else {
            assertThat(status).isEqualTo("SCHEDULED");
            assertThat(hold.result()).isInstanceOf(HoldResult.class);
            assertThat(activeHolds).isOne();
            assertThat(cancel.error()).isInstanceOfSatisfying(ApiException.class,
                    api -> assertThat(api.code()).isEqualTo("SHOWTIME_HAS_ACTIVE_HOLDS"));
        }
        assertThat(db.count("booking")).isZero();
    }

    @Test
    void cancelRacingAPaymentNeverCancelsASoldShowAndNeverVoidsACapture() throws Exception {
        UUID holdId = holdService.createHold(alice, starlightShow, List.of(seats.get(0))).holdId();
        List<Outcome<Object>> outcomes = runTogether(List.of(
                () -> adminShowtimeService.cancelShowtime(dallasAdmin, starlightShow),
                () -> checkoutService.pay(alice, holdId, "cancel-race-pay", PaymentScenario.SUCCESS)));

        Outcome<Object> cancel = outcomes.get(0);
        Outcome<Object> payment = outcomes.get(1);

        assertThat(cancel.error()).isInstanceOfSatisfying(ApiException.class,
                api -> assertThat(api.code()).isIn("SHOWTIME_HAS_ACTIVE_HOLDS", "SHOWTIME_HAS_UNRESOLVED_PAYMENTS",
                        "SHOWTIME_HAS_BOOKINGS"));
        assertThat(db.showtimeStatus(starlightShow)).isEqualTo("SCHEDULED");
        if (payment.result() != null) {
            PaymentOutcome booked = (PaymentOutcome) payment.result();
            assertThat(booked.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(db.count("booking")).isOne();
            assertThat(db.holdStatus(holdId)).isEqualTo("CONVERTED");
        } else {
            assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");
            assertThat(db.count("booking")).isZero();
        }
        verify(provider, never()).voidCharge(anyString());
    }

    private void holdServiceCreateClosed() {
        try {
            holdService.createHold(alice, starlightShow, List.of(seats.get(0)));
            throw new AssertionError("hold on a cancelled showtime should fail");
        } catch (ApiException ex) {
            assertThat(ex.code()).isEqualTo("SHOWTIME_CLOSED");
        }
    }

    private long theaterId(String name) {
        return jdbc.queryForObject("SELECT id FROM theater WHERE name = ?", Long.class, name);
    }

    private ResultActions cancel(long showtimeId, RequestPostProcessor user) throws Exception {
        var request = post("/api/admin/showtimes/{id}/cancel", showtimeId).contentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            request = request.with(user);
        }
        return mockMvc.perform(request);
    }

    private static <T> List<Outcome<T>> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start latch");
                    }
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Exception ex) {
                        return new Outcome<>(null, ex);
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private record Outcome<T>(T result, Exception error) {
    }
}
