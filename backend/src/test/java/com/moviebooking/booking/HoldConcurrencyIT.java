package com.moviebooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moviebooking.MutableClock;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;
import com.moviebooking.booking.HoldService.HoldResult;
import com.moviebooking.config.ApiException;
import com.moviebooking.security.AuthenticatedUser;

/**
 * Concurrent hold requests against PostgreSQL. Threads are released together by a latch and call
 * the real transactional service; outcomes are checked against the stored rows. Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class HoldConcurrencyIT {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String DEADLOCK_DETECTED = "40P01";

    @Autowired
    private HoldService holdService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private HoldFixtures db;

    @BeforeEach
    void setUp() {
        db = new HoldFixtures(jdbcTemplate);
        db.reset();
        clock.set(TestClockConfiguration.START);
    }

    @AfterEach
    void tearDown() {
        db.reset();
    }

    @Test
    void manyCustomersRacingForOneSeatProduceExactlyOneHolder() throws Exception {
        long showtime = db.showtimes(1).getFirst();
        long seat = db.seatIds(showtime, 1).getFirst();
        List<AuthenticatedUser> customers = db.createCustomers(40);

        List<Outcome> outcomes = runTogether(customers.stream()
                .<Callable<HoldResult>>map(customer -> () -> holdService.createHold(customer, showtime, List.of(seat)))
                .toList());

        List<HoldResult> winners = successes(outcomes);
        assertThat(winners).hasSize(1);
        assertOnlyExpectedFailures(outcomes);
        assertThat(db.heldSeats(showtime)).isEqualTo(Map.of(seat, winners.getFirst().holdId()));
        assertThat(db.holdCount()).isOne();
    }

    @Test
    void twoCustomersRacingForTheSameSeatAlwaysLeaveOneWinner() throws Exception {
        AuthenticatedUser alice = db.principal("alice");
        AuthenticatedUser bob = db.principal("bob");

        for (long showtime : db.showtimes(20)) {
            long seat = db.seatIds(showtime, 1).getFirst();

            List<Outcome> outcomes = runTogether(List.of(
                    () -> holdService.createHold(alice, showtime, List.of(seat)),
                    () -> holdService.createHold(bob, showtime, List.of(seat))));

            List<HoldResult> winners = successes(outcomes);
            assertThat(winners).as("showtime %d", showtime).hasSize(1);
            assertOnlyExpectedFailures(outcomes);
            assertThat(db.heldSeats(showtime)).isEqualTo(Map.of(seat, winners.getFirst().holdId()));
        }
        assertThat(db.holdCount()).isEqualTo(20);
    }

    @Test
    void overlappingMultiSeatRequestsNeverDeadlockOrPartiallySucceed() throws Exception {
        long showtime = db.showtimes(1).getFirst();
        List<Long> pool = db.seatIds(showtime, 6);
        List<AuthenticatedUser> customers = db.createCustomers(30);
        Random random = new Random(20261102L);

        List<List<Long>> requests = new ArrayList<>();
        List<Callable<HoldResult>> tasks = new ArrayList<>();
        for (AuthenticatedUser customer : customers) {
            List<Long> seats = new ArrayList<>(pool);
            Collections.shuffle(seats, random);
            List<Long> request = List.copyOf(seats.subList(0, 2 + random.nextInt(3)));
            requests.add(request);
            tasks.add(() -> holdService.createHold(customer, showtime, request));
        }

        List<Outcome> outcomes = runTogether(tasks);

        assertOnlyExpectedFailures(outcomes);
        assertThat(successes(outcomes)).isNotEmpty();
        Map<Long, UUID> held = db.heldSeats(showtime);
        int heldBySuccesses = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome outcome = outcomes.get(i);
            if (outcome.result() != null) {
                UUID holdId = outcome.result().holdId();
                assertThat(db.seatsHeldBy(holdId)).as("all seats of a successful hold")
                        .containsExactlyInAnyOrderElementsOf(requests.get(i));
                heldBySuccesses += requests.get(i).size();
            } else {
                assertThat(db.holdCountOf(customers.get(i).id())).as("a failed request leaves no hold").isZero();
            }
        }
        assertThat(held).hasSize(heldBySuccesses);
        assertThat(db.activeHoldCount()).isEqualTo(successes(outcomes).size());
    }

    @Test
    void sameCustomerRacingTwoHoldsForOneShowtimeGetsExactlyOne() throws Exception {
        AuthenticatedUser alice = db.principal("alice");

        for (long showtime : db.showtimes(10)) {
            List<Long> seats = db.seatIds(showtime, 2);

            List<Outcome> outcomes = runTogether(List.of(
                    () -> holdService.createHold(alice, showtime, List.of(seats.get(0))),
                    () -> holdService.createHold(alice, showtime, List.of(seats.get(1)))));

            assertThat(successes(outcomes)).as("showtime %d", showtime).hasSize(1);
            Outcome loser = outcomes.stream().filter(o -> o.error() != null).findFirst().orElseThrow();
            assertThat(loser.error()).isInstanceOf(ApiException.class);
            assertThat(((ApiException) loser.error()).code()).isEqualTo("ACTIVE_HOLD_EXISTS");
            assertThat(db.heldSeats(showtime)).hasSize(1);
        }
        assertThat(db.activeHoldCount()).isEqualTo(10);
    }

    @Test
    void lockWaitIsBoundedAndReportedAsSeatsBusyWithNothingHeld() throws Exception {
        long showtime = db.showtimes(1).getFirst();
        List<Long> seats = db.seatIds(showtime, 2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> blocker = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbcTemplate.queryForList("SELECT seat_id FROM show_seat WHERE showtime_id = ? AND seat_id = ? FOR UPDATE",
                        showtime, seats.get(1));
                locked.countDown();
                await(finish);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            mockMvc.perform(post("/api/showtimes/{id}/holds", showtime).with(httpBasic("alice", "alice-demo-pass"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\":[" + seats.get(0) + "," + seats.get(1) + "]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SEATS_BUSY"));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(waited).isBetween(Duration.ofMillis(1900), Duration.ofSeconds(10));
            assertThat(db.holdCount()).isZero();
            assertThat(db.heldSeats(showtime)).isEmpty();

            finish.countDown();
            blocker.get(30, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }

        assertThat(holdService.createHold(db.principal("alice"), showtime, seats).seatIds())
                .as("the seats are holdable once the other transaction ends")
                .containsExactlyElementsOf(seats);
    }

    /** Starts every task at the same instant (latch) and collects each result or failure. */
    private static List<Outcome> runTogether(List<Callable<HoldResult>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<HoldResult> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        return new Outcome(task.call(), null);
                    } catch (Exception ex) {
                        return new Outcome(null, ex);
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<HoldResult> successes(List<Outcome> outcomes) {
        return outcomes.stream().map(Outcome::result).filter(r -> r != null).toList();
    }

    /**
     * Losers may only see a business conflict or a bounded lock wait; any deadlock (40P01) or
     * other error fails the test.
     */
    private static void assertOnlyExpectedFailures(List<Outcome> outcomes) {
        for (Outcome outcome : outcomes) {
            Exception error = outcome.error();
            if (error == null) {
                continue;
            }
            if (error instanceof ApiException api) {
                assertThat(api.code()).isEqualTo("SEATS_UNAVAILABLE");
            } else {
                assertThat(error).isInstanceOf(PessimisticLockingFailureException.class);
                assertThat(sqlState(error)).isNotEqualTo(DEADLOCK_DETECTED).isEqualTo(LOCK_NOT_AVAILABLE);
            }
        }
    }

    private static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch was not released");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private record Outcome(HoldResult result, Exception error) {
    }
}
