package com.moviebooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.MutableClock;
import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;

/**
 * Outbox relay against PostgreSQL. The scheduler is disabled; tests call {@link OutboxDispatcher}
 * with the mutable Clock. Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class OutboxDeliveryIT {

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
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private OutboxRelayTransactions relay;

    @Autowired
    private OutboxEventRepository outbox;

    @MockitoSpyBean
    private NotificationInboxWriter writer;

    private HoldFixtures db;
    private long showtime;
    private List<Long> seats;
    private long aliceId;

    @BeforeEach
    void setUp() {
        db = new HoldFixtures(jdbc);
        db.reset();
        clock.set(TestClockConfiguration.START);
        showtime = db.showtimes(1).getFirst();
        seats = db.seatIds(showtime, 8);
        aliceId = db.userId("alice");
    }

    @AfterEach
    void tearDown() {
        reset(writer);
        db.reset();
    }

    @Test
    void aConfirmedBookingWritesAnUnpublishedEventAndRelayCreatesOneNotification() throws Exception {
        payAlice(seats.get(0));

        assertThat(db.count("booking")).isOne();
        assertThat(db.count("outbox_event")).isOne();
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event", Timestamp.class)).isNull();
        assertThat(db.count("notification_inbox")).isZero();

        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(db.count("notification_inbox")).isOne();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_event", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT customer_id FROM notification_inbox", Long.class)).isEqualTo(aliceId);
        assertThat(jdbc.queryForObject("SELECT event_type FROM notification_inbox", String.class))
                .isEqualTo("BookingConfirmed");
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_event", UUID.class);
        assertThat(jdbc.queryForObject("SELECT event_id FROM notification_inbox", UUID.class)).isEqualTo(eventId);
    }

    @Test
    void reprocessingTheSameEventDoesNotDuplicateTheNotification() throws Exception {
        payAlice(seats.get(0));
        assertThat(dispatcher.dispatchDue()).isOne();
        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(db.count("notification_inbox")).isOne();
        assertThat(db.count("outbox_event")).isOne();
    }

    @Test
    void aCrashAfterInboxWriteIsRecoveredWithoutADuplicate() throws Exception {
        payAlice(seats.get(0));
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_event", UUID.class);
        UUID bookingId = jdbc.queryForObject("SELECT aggregate_id FROM outbox_event", UUID.class);
        jdbc.update("""
                INSERT INTO notification_inbox (id, event_id, customer_id, booking_id, event_type, payload, created_at)
                SELECT ?, id, CAST(payload ->> 'customerId' AS bigint), ?, event_type, payload, created_at
                FROM outbox_event WHERE id = ?
                """, UUID.randomUUID(), bookingId, eventId);

        assertThat(dispatcher.dispatchDue()).isOne();
        assertThat(db.count("notification_inbox")).isOne();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_event", Boolean.class)).isTrue();
    }

    @Test
    void anInterruptedClaimIsNotStrandedAfterTheLeaseExpires() throws Exception {
        payAlice(seats.get(0));
        Instant leaseUntil = TestClockConfiguration.START.plus(Duration.ofMinutes(30));
        jdbc.update("UPDATE outbox_event SET claimed_until = ?, attempt_count = 1", Timestamp.from(leaseUntil));

        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(db.count("notification_inbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event", Timestamp.class)).isNull();

        clock.advance(Duration.ofMinutes(30).plusSeconds(1));
        assertThat(dispatcher.dispatchDue()).isOne();
        assertThat(db.count("notification_inbox")).isOne();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_event", Boolean.class)).isTrue();
    }

    @Test
    void aSimulatedFailureRetriesAndThenSucceeds() throws Exception {
        payAlice(seats.get(0));
        doThrow(new IllegalStateException("simulated consumer failure")).doCallRealMethod()
                .when(writer).record(any(), any());

        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(db.count("notification_inbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event", String.class))
                .contains("simulated consumer failure");
        Instant next = jdbc.queryForObject("SELECT next_attempt_at FROM outbox_event", Instant.class);
        assertThat(next).isAfter(TestClockConfiguration.START);

        assertThat(dispatcher.dispatchDue()).as("not due until backoff elapses").isZero();

        clock.set(next);
        doCallRealMethod().when(writer).record(any(), any());
        assertThat(dispatcher.dispatchDue()).isOne();
        assertThat(db.count("notification_inbox")).isOne();
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event", String.class)).isNull();
    }

    @Test
    void concurrentInboxWritesForTheSameEventInsertOneRow() throws Exception {
        payAlice(seats.get(0));
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_event", UUID.class);
        OutboxEvent event = outbox.findById(eventId).orElseThrow();
        Instant now = clock.instant();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    writer.record(event, now);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.count("notification_inbox")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT event_id) FROM notification_inbox", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT event_id FROM notification_inbox", UUID.class)).isEqualTo(eventId);
    }

    @Test
    void aStaleWorkerCannotAckOrFailAfterAnotherWorkerReclaims() throws Exception {
        payAlice(seats.get(0));
        Duration lease = Duration.ofSeconds(30);
        List<OutboxEvent> claimedA = relay.claimDue(clock.instant(), 20, 8, lease);
        assertThat(claimedA).hasSize(1);
        UUID eventId = claimedA.get(0).getId();
        UUID tokenA = claimedA.get(0).getClaimToken();
        assertThat(tokenA).isNotNull();
        assertThat(jdbc.queryForObject("SELECT claim_token FROM outbox_event WHERE id = ?", UUID.class, eventId))
                .isEqualTo(tokenA);
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event", Timestamp.class)).isNull();

        clock.advance(lease.plusSeconds(1));
        List<OutboxEvent> claimedB = relay.claimDue(clock.instant(), 20, 8, lease);
        assertThat(claimedB).hasSize(1);
        UUID tokenB = claimedB.get(0).getClaimToken();
        assertThat(claimedB.get(0).getId()).isEqualTo(eventId);
        assertThat(tokenB).isNotEqualTo(tokenA);
        assertThat(jdbc.queryForObject("SELECT claim_token FROM outbox_event WHERE id = ?", UUID.class, eventId))
                .isEqualTo(tokenB);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_event", Integer.class)).isEqualTo(2);

        assertThat(relay.markPublished(eventId, tokenA, clock.instant())).isFalse();
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT claim_token FROM outbox_event WHERE id = ?", UUID.class, eventId))
                .isEqualTo(tokenB);

        Instant staleNext = clock.instant().plusSeconds(5);
        assertThat(relay.recordFailure(eventId, tokenA, "stale worker", staleNext)).isFalse();
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT claim_token FROM outbox_event WHERE id = ?", UUID.class, eventId))
                .isEqualTo(tokenB);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM outbox_event", Instant.class))
                .isNotEqualTo(staleNext);

        writer.record(claimedB.get(0), clock.instant());
        assertThat(relay.markPublished(eventId, tokenB, clock.instant())).isTrue();
        assertThat(db.count("notification_inbox")).isOne();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_event", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT claim_token FROM outbox_event WHERE id = ?", UUID.class, eventId))
                .isNull();
    }

    @Test
    void twoConcurrentWorkersNeitherLoseNorDuplicateDelivery() throws Exception {
        for (int i = 0; i < 6; i++) {
            insertUnpublished("REF" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return dispatcher.dispatchDue();
                }));
            }
            start.countDown();
            int delivered = 0;
            for (Future<Integer> result : results) {
                delivered += result.get(30, TimeUnit.SECONDS);
            }
            assertThat(delivered).isEqualTo(6);
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.count("notification_inbox")).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT event_id) FROM notification_inbox", Integer.class))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class))
                .isZero();
    }

    @Test
    void declinedAndUnconfirmedBookingsCreateNeitherEventNorNotification() throws Exception {
        UUID holdId = hold(ALICE, seats.get(0));
        mockMvc.perform(post("/api/holds/{id}/payment", holdId).with(ALICE)
                        .header("Idempotency-Key", "key-decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"DECLINE\"}"))
                .andExpect(status().isPaymentRequired());

        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(db.count("booking")).isZero();
        assertThat(db.count("outbox_event")).isZero();
        assertThat(db.count("notification_inbox")).isZero();
    }

    @Test
    void notificationListIsOwnedByTheCaller() throws Exception {
        payAlice(seats.get(0));
        dispatcher.dispatchDue();

        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications").with(DALLAS_ADMIN)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notifications").with(BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/notifications").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("BookingConfirmed"))
                .andExpect(jsonPath("$.items[0].reference").isNotEmpty())
                .andExpect(jsonPath("$.items[0].bookingId").isNotEmpty());
    }

    private void payAlice(Long seatId) throws Exception {
        UUID holdId = hold(ALICE, seatId);
        mockMvc.perform(post("/api/holds/{id}/payment", holdId).with(ALICE)
                        .header("Idempotency-Key", "key-" + seatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"SUCCESS\"}"))
                .andExpect(status().isCreated());
    }

    private UUID hold(RequestPostProcessor user, Long... seatIds) throws Exception {
        String body = mockMvc.perform(post("/api/showtimes/{id}/holds", showtime).with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldController.CreateHoldRequest(List.of(seatIds)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        try {
            JsonNode json = objectMapper.readTree(body);
            return UUID.fromString(json.get("holdId").asText());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void insertUnpublished(String reference) {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Instant now = clock.instant();
        String payload = """
                {"bookingId":"%s","reference":"%s","customerId":%d,"showtimeId":%d,"seatIds":[1],"totalAmount":"10.00","confirmedAt":"%s"}
                """.formatted(bookingId, reference, aliceId, showtime, now);
        jdbc.update("""
                INSERT INTO outbox_event
                    (id, aggregate_type, aggregate_id, event_type, payload, created_at, next_attempt_at, attempt_count)
                VALUES (?, 'Booking', ?, 'BookingConfirmed', CAST(? AS jsonb), ?, ?, 0)
                """, id, bookingId, payload, Timestamp.from(now), Timestamp.from(now));
    }
}
