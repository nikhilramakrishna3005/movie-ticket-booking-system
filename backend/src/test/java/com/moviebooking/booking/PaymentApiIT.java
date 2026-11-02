package com.moviebooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.MutableClock;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;
import com.moviebooking.booking.CheckoutService.PaymentOutcome;
import com.moviebooking.payment.PaymentScenario;
import com.moviebooking.payment.PaymentStatus;
import com.moviebooking.payment.SimulatedPaymentProvider;

/**
 * Payment and booking API against PostgreSQL with the seeded demo users. The real simulated
 * provider is wrapped in a spy so tests can count calls and inject delays. Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class PaymentApiIT {

    private static final RequestPostProcessor ALICE = httpBasic("alice", "alice-demo-pass");
    private static final RequestPostProcessor BOB = httpBasic("bob", "bob-demo-pass");
    private static final RequestPostProcessor DALLAS_ADMIN = httpBasic("admin.dallas", "dallas-admin-demo");
    private static final Duration TTL = Duration.ofMinutes(8);
    private static final Duration PAYMENT_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CheckoutService checkoutService;

    @MockitoSpyBean
    private SimulatedPaymentProvider provider;

    private HoldFixtures db;
    private long showtime;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        db = new HoldFixtures(jdbcTemplate);
        db.reset();
        clock.set(TestClockConfiguration.START);
        showtime = db.showtimes(1).getFirst();
        seats = db.seatIds(showtime, 4);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_outbox_insert ON outbox_event");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_outbox_insert()");
        db.reset();
    }

    @Test
    void successfulPaymentConfirmsOneBookingAndBooksTheSeats() throws Exception {
        UUID holdId = hold(ALICE, seats.get(1), seats.get(0));

        String body = pay(ALICE, holdId, "key-success", "SUCCESS")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.holdId").value(holdId.toString()))
                .andExpect(jsonPath("$.booking.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.booking.showtimeId").value((int) showtime))
                .andExpect(jsonPath("$.booking.seats[*].seatId",
                        contains(seats.get(0).intValue(), seats.get(1).intValue())))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = json(body);
        JsonNode booking = response.get("booking");
        UUID bookingId = UUID.fromString(booking.get("bookingId").asText());
        BigDecimal expectedTotal = priceOf(seats.get(0), seats.get(1));
        assertThat(booking.get("reference").asText()).matches("^[A-Z0-9]{6,12}$");
        assertThat(booking.get("movieTitle").asText()).isNotBlank();
        assertThat(booking.get("theaterName").asText()).isNotBlank();
        assertThat(booking.get("screenName").asText()).isNotBlank();
        assertThat(booking.get("city").asText()).matches(".+, [A-Z]{2}");
        assertThat(booking.get("startsAt").asText()).isEqualTo(db.startsAt(showtime).toString());
        assertThat(booking.get("seats").get(0).get("rowLabel").asText()).isNotBlank();
        assertThat(new BigDecimal(booking.get("totalAmount").asText())).isEqualByComparingTo(expectedTotal);
        assertThat(new BigDecimal(response.get("amount").asText())).isEqualByComparingTo(expectedTotal);
        assertThat(body).as("no payment internals in the response")
                .doesNotContain("key-success").doesNotContain("scenario").doesNotContain("idempotency");

        assertThat(db.count("booking")).isOne();
        assertThat(db.seatsBookedBy(bookingId)).containsExactly(seats.get(0), seats.get(1));
        assertThat(db.bookingSeatIds(bookingId)).containsExactly(seats.get(0), seats.get(1));
        assertThat(db.holdStatus(holdId)).isEqualTo("CONVERTED");
        assertThat(db.seatsHeldBy(holdId)).isEmpty();
        assertThat(db.ledgerStatus("key-success")).isEqualTo("CAPTURED");
        assertThat(jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_event WHERE aggregate_type = 'Booking' AND aggregate_id = ?",
                String.class, bookingId)).containsExactly("BookingConfirmed");
        assertThat(jdbcTemplate.queryForObject("SELECT payload ->> 'reference' FROM outbox_event", String.class))
                .isEqualTo(booking.get("reference").asText());

        mockMvc.perform(get("/api/bookings/{id}", bookingId).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(booking.get("reference").asText()))
                .andExpect(jsonPath("$.seats.length()").value(2));
        mockMvc.perform(get("/api/payments/{id}", response.get("paymentId").asText()).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.booking.bookingId").value(bookingId.toString()));
        mockMvc.perform(get("/api/showtimes/{id}/seats", showtime).with(BOB))
                .andExpect(jsonPath("$.seats[?(@.seatId == " + seats.get(0) + ")].status", contains("BOOKED")));
    }

    @Test
    void declineCreatesNoBookingAndARetryNeedsANewKey() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0));

        String body = pay(ALICE, holdId, "key-decline", "DECLINE")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.details.status").value("DECLINED"))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(json(body).get("details").get("paymentId").asText());

        assertThat(db.count("booking")).isZero();
        assertThat(db.paymentStatus(paymentId)).isEqualTo("DECLINED");
        assertThat(db.failureReason(paymentId)).isEqualTo("PROVIDER_DECLINED");
        assertThat(db.holdStatus(holdId)).as("a decline leaves the hold usable").isEqualTo("ACTIVE");
        assertThat(db.seatsHeldBy(holdId)).containsExactly(seats.get(0));

        pay(ALICE, holdId, "key-decline", "DECLINE")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.details.paymentId").value(paymentId.toString()));
        pay(ALICE, holdId, "key-decline", "SUCCESS")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        verify(provider, times(1)).charge(anyString(), any(), any());
        assertThat(db.count("payment_attempt")).isOne();
        assertThat(db.count("simulated_provider_charge")).isOne();

        pay(ALICE, holdId, "key-retry", "SUCCESS").andExpect(status().isCreated());
        assertThat(db.count("booking")).isOne();
    }

    @Test
    void timeoutIsUnknownUntilAPollFindsTheCaptureAndConfirms() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0));

        String body = pay(ALICE, holdId, "key-timeout", "TIMEOUT")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.booking").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(json(body).get("paymentId").asText());

        assertThat(db.paymentStatus(paymentId)).as("never silently treated as failed").isEqualTo("UNKNOWN");
        assertThat(db.ledgerStatus("key-timeout")).isEqualTo("CAPTURED");
        assertThat(db.count("booking")).isZero();
        assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");

        mockMvc.perform(get("/api/payments/{id}", paymentId).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.booking.reference").isNotEmpty());
        assertThat(db.count("booking")).isOne();
        assertThat(db.holdStatus(holdId)).isEqualTo("CONVERTED");
        verify(provider, times(1)).charge(anyString(), any(), any());
    }

    @Test
    void unknownPaymentResolvedAfterHoldExpiryIsVoidedAndBooksNothing() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0));
        UUID paymentId = paymentId(pay(ALICE, holdId, "key-timeout", "TIMEOUT").andExpect(status().isAccepted()));

        clock.advance(TTL);
        mockMvc.perform(get("/api/payments/{id}", paymentId).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));

        assertThat(db.count("booking")).isZero();
        assertThat(db.ledgerStatus("key-timeout")).as("the capture was refunded").isEqualTo("VOIDED");
        assertThat(db.holdStatus(holdId)).isEqualTo("EXPIRED");
        assertThat(db.seatStatus(showtime, seats.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void captureArrivingAfterHoldExpiryIsVoidedEvenThoughTheSeatsAreFree() throws Exception {
        doAnswer(invocation -> {
            Object captured = invocation.callRealMethod();
            clock.advance(TTL);
            return captured;
        }).when(provider).charge(anyString(), any(), any());
        UUID holdId = hold(ALICE, seats.get(0));

        pay(ALICE, holdId, "key-late", "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_VOIDED"))
                .andExpect(jsonPath("$.details.status").value("VOIDED"));

        assertThat(db.count("booking")).isZero();
        assertThat(db.count("outbox_event")).isZero();
        assertThat(db.ledgerStatus("key-late")).isEqualTo("VOIDED");
        assertThat(db.holdStatus(holdId)).isEqualTo("EXPIRED");
        assertThat(db.seatStatus(showtime, seats.get(0))).isEqualTo("AVAILABLE");
        verify(provider, times(1)).voidCharge("key-late");
    }

    @Test
    void replayingAKeyReturnsTheSameBookingWithoutChargingAgain() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0), seats.get(1));
        String first = pay(ALICE, holdId, "key-replay", "SUCCESS").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = pay(ALICE, holdId, "key-replay", "SUCCESS").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(json(second).get("booking").get("bookingId"))
                .isEqualTo(json(first).get("booking").get("bookingId"));
        verify(provider, times(1)).charge(anyString(), any(), any());
        assertThat(db.count("payment_attempt")).isOne();
        assertThat(db.count("simulated_provider_charge")).isOne();
        assertThat(db.count("booking")).isOne();
        assertThat(db.count("outbox_event")).isOne();
    }

    @Test
    void keyReuseForAnotherHoldIsRejected() throws Exception {
        UUID first = hold(ALICE, seats.get(0));
        long otherShowtime = db.showtimes(2).get(1);
        UUID second = holdOn(ALICE, otherShowtime, db.seatIds(otherShowtime, 1).getFirst());
        pay(ALICE, first, "key-shared", "DECLINE").andExpect(status().isPaymentRequired());

        pay(ALICE, second, "key-shared", "DECLINE")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(db.count("payment_attempt")).isOne();
    }

    @Test
    void customersCannotPayForOrSeeAnotherCustomersHoldPaymentOrBooking() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0));

        pay(BOB, holdId, "key-bob", "SUCCESS")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(db.count("payment_attempt")).isZero();

        String body = pay(ALICE, holdId, "key-alice", "SUCCESS").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String paymentId = json(body).get("paymentId").asText();
        String bookingId = json(body).get("booking").get("bookingId").asText();

        pay(BOB, holdId, "key-alice", "SUCCESS").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/payments/{id}", paymentId).with(BOB)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bookings/{id}", bookingId).with(BOB)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bookings/{id}", bookingId).with(DALLAS_ADMIN)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payments/{id}", UUID.randomUUID()).with(ALICE)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bookings/{id}", UUID.randomUUID()).with(ALICE)).andExpect(status().isNotFound());
        verify(provider, times(1)).charge(anyString(), any(), any());
    }

    @Test
    void holdCannotBeReleasedOrPaidAgainWhileAPaymentIsInFlight() throws Exception {
        CountDownLatch charging = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            charging.countDown();
            assertThat(proceed.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(provider).charge(anyString(), any(), any());
        UUID holdId = hold(ALICE, seats.get(0));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentOutcome> inFlight = executor.submit(() -> checkoutService.pay(db.principal("alice"),
                    holdId, "key-inflight", PaymentScenario.SUCCESS));
            assertThat(charging.await(30, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(delete("/api/holds/{id}", holdId).with(ALICE))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));
            pay(ALICE, holdId, "key-second", "SUCCESS")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));
            pay(ALICE, holdId, "key-inflight", "SUCCESS")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));
            assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");

            proceed.countDown();
            assertThat(inFlight.get(30, TimeUnit.SECONDS).payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
        assertThat(db.count("booking")).isOne();
        verify(provider, times(1)).charge(anyString(), any(), any());
    }

    /**
     * Ordering: TX2 commits PENDING, the request thread stalls before its charge reaches the
     * provider, a poll after the payment timeout resolves the attempt, and only then does the
     * stalled charge run. Controlled by latches; the clock is advanced explicitly.
     */
    @Test
    void stalledChargeArrivingAfterAStaleResolutionCannotCapture() throws Exception {
        CountDownLatch charging = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            charging.countDown();
            assertThat(proceed.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(provider).charge(anyString(), any(), any());
        UUID holdId = hold(ALICE, seats.get(0));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentOutcome> stalled = executor.submit(() -> checkoutService.pay(db.principal("alice"),
                    holdId, "key-stalled", PaymentScenario.SUCCESS));
            assertThat(charging.await(30, TimeUnit.SECONDS)).isTrue();
            UUID paymentId = jdbcTemplate.queryForObject(
                    "SELECT id FROM payment_attempt WHERE idempotency_key = 'key-stalled'", UUID.class);

            mockMvc.perform(get("/api/payments/{id}", paymentId).with(ALICE))
                    .andExpect(jsonPath("$.status").value("PENDING"));
            clock.advance(PAYMENT_TIMEOUT);
            mockMvc.perform(get("/api/payments/{id}", paymentId).with(ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DECLINED"));
            assertThat(db.failureReason(paymentId)).isEqualTo("NOT_RECEIVED");
            assertThat(db.ledgerStatus("key-stalled")).as("the key is fenced").isEqualTo("DECLINED");

            proceed.countDown();
            assertThat(stalled.get(30, TimeUnit.SECONDS).payment().status()).isEqualTo(PaymentStatus.DECLINED);
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }

        assertThat(db.ledgerStatus("key-stalled")).as("the late charge did not capture").isEqualTo("DECLINED");
        assertThat(db.count("booking")).isZero();
        verify(provider, never()).voidCharge(anyString());
        assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");

        pay(ALICE, holdId, "key-retry", "SUCCESS").andExpect(status().isCreated());
        assertThat(db.count("booking")).isOne();
        assertThat(db.ledgerStatus("key-stalled")).isEqualTo("DECLINED");
    }

    @Test
    void holdWithUnknownPaymentCannotBeReleased() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0));
        pay(ALICE, holdId, "key-timeout", "TIMEOUT").andExpect(status().isAccepted());

        mockMvc.perform(delete("/api/holds/{id}", holdId).with(ALICE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));
        assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(db.seatsHeldBy(holdId)).containsExactly(seats.get(0));
    }

    @Test
    void providerIsOnlyEverCalledOutsideADatabaseTransaction() throws Exception {
        List<Boolean> transactionActive = Collections.synchronizedList(new ArrayList<>());
        Answer<Object> recordTransactionState = invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        };
        doAnswer(recordTransactionState).when(provider).charge(anyString(), any(), any());
        doAnswer(recordTransactionState).when(provider).statusOrFence(anyString(), any());
        doAnswer(recordTransactionState).when(provider).voidCharge(anyString());

        pay(ALICE, hold(ALICE, seats.get(0)), "key-a", "SUCCESS").andExpect(status().isCreated());
        UUID unknown = paymentId(pay(BOB, hold(BOB, seats.get(1)), "key-b", "TIMEOUT")
                .andExpect(status().isAccepted()));
        clock.advance(TTL);
        mockMvc.perform(get("/api/payments/{id}", unknown).with(BOB))
                .andExpect(jsonPath("$.status").value("VOIDED"));

        verify(provider, times(2)).charge(anyString(), any(), any());
        verify(provider, times(1)).statusOrFence(eq("key-b"), any());
        verify(provider, times(1)).voidCharge("key-b");
        assertThat(transactionActive).hasSize(4).containsOnly(false);
    }

    @Test
    void providerRefusesToRunInsideATransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                tx -> provider.charge("key-in-tx", BigDecimal.TEN, PaymentScenario.SUCCESS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a database transaction");
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> provider.voidCharge("key-in-tx")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.ledgerStatus("key-in-tx")).isNull();
    }

    @Test
    void failedConfirmationRollsBackEverythingAndIsRetriedByAPollOnceStale() throws Exception {
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_outbox_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated outbox failure'; END $$""");
        jdbcTemplate.execute("CREATE TRIGGER fail_outbox_insert BEFORE INSERT ON outbox_event"
                + " FOR EACH ROW EXECUTE FUNCTION fail_outbox_insert()");
        UUID holdId = hold(ALICE, seats.get(0), seats.get(1));

        String body = pay(ALICE, holdId, "key-atomic", "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(json(body).get("details").get("paymentId").asText());

        assertThat(db.count("booking")).isZero();
        assertThat(db.count("booking_seat")).isZero();
        assertThat(db.count("outbox_event")).isZero();
        assertThat(db.seatsHeldBy(holdId)).containsExactly(seats.get(0), seats.get(1));
        assertThat(db.holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(db.paymentStatus(paymentId)).isEqualTo("PENDING");
        assertThat(db.ledgerStatus("key-atomic")).isEqualTo("CAPTURED");

        jdbcTemplate.execute("DROP TRIGGER fail_outbox_insert ON outbox_event");
        mockMvc.perform(get("/api/payments/{id}", paymentId).with(ALICE))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(provider, never()).statusOrFence(anyString(), any());

        clock.advance(PAYMENT_TIMEOUT);
        mockMvc.perform(get("/api/payments/{id}", paymentId).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(db.count("booking")).isOne();
        assertThat(db.count("outbox_event")).isOne();
        verify(provider, times(1)).charge(anyString(), any(), any());
    }

    @Test
    void paymentNeedsAnActiveHoldWithEnoughTimeLeft() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0));

        clock.advance(TTL.minusSeconds(30));
        pay(ALICE, holdId, "key-1", "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_TOO_CLOSE_TO_EXPIRY"));
        clock.advance(Duration.ofSeconds(30));
        pay(ALICE, holdId, "key-2", "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));

        clock.set(TestClockConfiguration.START);
        UUID released = hold(BOB, seats.get(1));
        mockMvc.perform(delete("/api/holds/{id}", released).with(BOB)).andExpect(status().isNoContent());
        pay(BOB, released, "key-3", "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));

        assertThat(db.count("payment_attempt")).isZero();
        verify(provider, never()).charge(anyString(), any(), any());
    }

    @Test
    void convertedHoldCannotBePaidAgainWithANewKey() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0));
        pay(ALICE, holdId, "key-1", "SUCCESS").andExpect(status().isCreated());

        pay(ALICE, holdId, "key-2", "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));
        mockMvc.perform(delete("/api/holds/{id}", holdId).with(ALICE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_CONVERTED"));
        assertThat(db.count("booking")).isOne();
    }

    @Test
    void attemptsPerHoldAreLimited() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0));
        for (int i = 0; i < CheckoutTransactions.MAX_ATTEMPTS_PER_HOLD; i++) {
            pay(ALICE, holdId, "key-" + i, "DECLINE").andExpect(status().isPaymentRequired());
        }

        pay(ALICE, holdId, "key-last", "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        assertThat(db.count("payment_attempt")).isEqualTo(CheckoutTransactions.MAX_ATTEMPTS_PER_HOLD);
    }

    private UUID hold(RequestPostProcessor user, Long... seatIds) throws Exception {
        return holdOn(user, showtime, seatIds);
    }

    private UUID holdOn(RequestPostProcessor user, long showtimeId, Long... seatIds) throws Exception {
        String body = mockMvc.perform(post("/api/showtimes/{id}/holds", showtimeId).with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldController.CreateHoldRequest(List.of(seatIds)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json(body).get("holdId").asText());
    }

    private ResultActions pay(RequestPostProcessor user, UUID holdId, String key, String scenario) throws Exception {
        return mockMvc.perform(post("/api/holds/{id}/payment", holdId).with(user)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"" + scenario + "\"}"));
    }

    private UUID paymentId(ResultActions result) throws Exception {
        return UUID.fromString(json(result.andReturn().getResponse().getContentAsString()).get("paymentId").asText());
    }

    private BigDecimal priceOf(Long... seatIds) {
        BigDecimal total = BigDecimal.ZERO;
        for (Long seatId : seatIds) {
            total = total.add(jdbcTemplate.queryForObject(
                    "SELECT price FROM show_seat WHERE showtime_id = ? AND seat_id = ?", BigDecimal.class,
                    showtime, seatId));
        }
        return total;
    }

    private JsonNode json(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
