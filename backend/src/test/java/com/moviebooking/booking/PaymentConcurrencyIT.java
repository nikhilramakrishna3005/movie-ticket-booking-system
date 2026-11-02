package com.moviebooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.moviebooking.MutableClock;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;
import com.moviebooking.booking.CheckoutService.PaymentOutcome;
import com.moviebooking.config.ApiException;
import com.moviebooking.payment.PaymentScenario;
import com.moviebooking.payment.PaymentStatus;
import com.moviebooking.payment.SimulatedPaymentProvider;
import com.moviebooking.security.AuthenticatedUser;

/**
 * Concurrent payment requests against PostgreSQL. Threads are released together by a latch and
 * call the real services; outcomes are checked against the stored rows. Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc // unused, but makes the context identical to PaymentApiIT's so it is shared
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class PaymentConcurrencyIT {

    private static final int THREADS = 12;
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private HoldService holdService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private SimulatedPaymentProvider provider;

    private HoldFixtures db;
    private AuthenticatedUser alice;

    @BeforeEach
    void setUp() {
        db = new HoldFixtures(jdbcTemplate);
        db.reset();
        clock.set(TestClockConfiguration.START);
        alice = db.principal("alice");
    }

    @AfterEach
    void tearDown() {
        db.reset();
    }

    @Test
    void concurrentRequestsWithTheSameKeyChargeOnceAndBookOnce() throws Exception {
        UUID holdId = newHold(db.showtimes(1).getFirst(), 2);

        List<Outcome<PaymentOutcome>> outcomes = runTogether(IntStream.range(0, THREADS)
                .<Callable<PaymentOutcome>>mapToObj(i -> () -> checkoutService.pay(alice, holdId, "key-same",
                        PaymentScenario.SUCCESS))
                .toList());

        Set<UUID> paymentIds = new HashSet<>();
        for (Outcome<PaymentOutcome> outcome : outcomes) {
            if (outcome.result() != null) {
                // Replays that arrive before the first request finished see it in progress.
                assertThat(outcome.result().payment().status()).isIn(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED);
                paymentIds.add(outcome.result().payment().id());
            } else {
                assertLockTimeout(outcome.error());
            }
        }
        assertThat(paymentIds).hasSize(1);
        assertThat(outcomes).anyMatch(o -> o.result() != null
                && o.result().payment().status() == PaymentStatus.SUCCEEDED);
        verify(provider, times(1)).charge(anyString(), any(), any());
        assertThat(db.count("payment_attempt")).isOne();
        assertThat(db.count("simulated_provider_charge")).isOne();
        assertThat(db.count("booking")).isOne();
        assertThat(db.count("outbox_event")).isOne();
        assertThat(db.holdStatus(holdId)).isEqualTo("CONVERTED");
        verify(provider, never()).voidCharge(anyString());
        assertThat(db.ledgerStatus("key-same")).isEqualTo("CAPTURED");
    }

    @Test
    void concurrentRequestsWithDifferentKeysProduceOneChargeAndOneBooking() throws Exception {
        UUID holdId = newHold(db.showtimes(1).getFirst(), 2);

        List<Outcome<PaymentOutcome>> outcomes = runTogether(IntStream.range(0, THREADS)
                .<Callable<PaymentOutcome>>mapToObj(i -> () -> checkoutService.pay(alice, holdId, "key-" + i,
                        PaymentScenario.SUCCESS))
                .toList());

        assertThat(outcomes).filteredOn(o -> o.result() != null).hasSize(1)
                .allMatch(o -> o.result().payment().status() == PaymentStatus.SUCCEEDED);
        for (Outcome<PaymentOutcome> outcome : outcomes) {
            if (outcome.error() instanceof ApiException api) {
                assertThat(api.code()).isIn("PAYMENT_IN_PROGRESS", "HOLD_NOT_ACTIVE");
            } else if (outcome.error() != null) {
                assertLockTimeout(outcome.error());
            }
        }
        verify(provider, times(1)).charge(anyString(), any(), any());
        assertThat(db.count("payment_attempt")).isOne();
        assertThat(db.count("booking")).isOne();
    }

    @Test
    void concurrentPollsOfAnUnknownPaymentConfirmItOnce() throws Exception {
        UUID holdId = newHold(db.showtimes(1).getFirst(), 2);
        UUID paymentId = checkoutService.pay(alice, holdId, "key-timeout", PaymentScenario.TIMEOUT).payment().id();
        assertThat(db.paymentStatus(paymentId)).isEqualTo("UNKNOWN");

        List<Outcome<PaymentOutcome>> outcomes = runTogether(IntStream.range(0, THREADS)
                .<Callable<PaymentOutcome>>mapToObj(i -> () -> checkoutService.getPayment(alice, paymentId))
                .toList());

        for (Outcome<PaymentOutcome> outcome : outcomes) {
            if (outcome.result() != null) {
                assertThat(outcome.result().payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
                assertThat(outcome.result().booking()).isNotNull();
            } else {
                assertLockTimeout(outcome.error());
            }
        }
        assertThat(db.count("booking")).isOne();
        assertThat(db.count("booking_seat")).isEqualTo(2);
        assertThat(db.count("outbox_event")).isOne();
        verify(provider, times(1)).charge(anyString(), any(), any());
        // A losing poll must see the booking, not mistake the converted hold for an invalid one and refund.
        verify(provider, never()).voidCharge(anyString());
        assertThat(db.ledgerStatus("key-timeout")).isEqualTo("CAPTURED");
    }

    /** Whichever wins, a released hold never ends up booked and a booked hold is never released. */
    @Test
    void releaseRacingAPaymentNeverLeavesAReleasedHoldBooked() throws Exception {
        for (long showtime : db.showtimes(10)) {
            UUID holdId = newHold(showtime, 1);
            List<Outcome<Object>> outcomes = runTogether(List.<Callable<Object>>of(
                    () -> checkoutService.pay(alice, holdId, "key-" + showtime, PaymentScenario.SUCCESS),
                    () -> {
                        holdService.releaseHold(alice, holdId);
                        return "released";
                    }));
            Outcome<Object> payment = outcomes.get(0);
            Outcome<Object> release = outcomes.get(1);

            String holdStatus = db.holdStatus(holdId);
            long bookings = jdbcTemplate.queryForObject("SELECT count(*) FROM booking WHERE hold_id = ?",
                    Long.class, holdId);
            if (release.result() != null) {
                assertThat(holdStatus).isEqualTo("RELEASED");
                assertThat(bookings).isZero();
                assertThat(payment.error()).isInstanceOfSatisfying(ApiException.class,
                        api -> assertThat(api.code()).isEqualTo("HOLD_NOT_ACTIVE"));
            } else {
                assertThat(holdStatus).isEqualTo("CONVERTED");
                assertThat(bookings).isOne();
                assertThat(release.error()).isInstanceOfSatisfying(ApiException.class,
                        api -> assertThat(api.code()).isIn("PAYMENT_IN_PROGRESS", "HOLD_CONVERTED"));
            }
        }
    }

    private UUID newHold(long showtime, int seatCount) {
        return holdService.createHold(alice, showtime, db.seatIds(showtime, seatCount)).holdId();
    }

    private static void assertLockTimeout(Exception error) {
        assertThat(error).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(sqlState(error)).isEqualTo(LOCK_NOT_AVAILABLE);
    }

    private static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** Starts every task at the same instant (latch) and collects each result or failure, in task order. */
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
                        throw new IllegalStateException("Latch was not released");
                    }
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Exception ex) {
                        return new Outcome<T>(null, ex);
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
