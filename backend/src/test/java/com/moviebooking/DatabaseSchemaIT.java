package com.moviebooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the Flyway schema, sample data, and database-level invariants against real PostgreSQL.
 * Each test runs in a rolled-back transaction, and an expected violation is always the last
 * statement because PostgreSQL aborts the transaction after an error.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class DatabaseSchemaIT {

    private static final Instant T0 = Instant.parse("2026-11-03T12:00:00Z");

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String EXCLUSION_VIOLATION = "23P01";

    @Autowired
    private JdbcTemplate jdbc;

    // --- migrations and sample data ---------------------------------------------------------

    @Test
    void allMigrationsApplyInOrder() {
        var versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    @Test
    void requiredExtensionsAreInstalled() {
        var extensions = jdbc.queryForList("SELECT extname FROM pg_extension", String.class);

        assertThat(extensions).contains("pg_trgm", "btree_gist");
    }

    @Test
    void sampleDataHasExpectedShape() {
        assertThat(count("SELECT count(*) FROM movie")).isEqualTo(8);
        assertThat(count("SELECT count(*) FROM city")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM theater")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM screen")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM seat")).isEqualTo(300);
        assertThat(count("SELECT count(*) FROM showtime")).isEqualTo(1080);
        assertThat(count("SELECT count(*) FROM show_seat")).isEqualTo(54_000);
        assertThat(count("SELECT count(*) FROM show_seat WHERE status <> 'AVAILABLE'")).isZero();
        assertThat(count("SELECT count(*) FROM movie m WHERE NOT EXISTS "
                + "(SELECT 1 FROM movie_genre mg WHERE mg.movie_id = m.id)")).isZero();
    }

    @Test
    void sampleShowtimesAreInsideTheDocumentedWindow() {
        var earliest = jdbc.queryForObject("SELECT min(starts_at) FROM showtime", Timestamp.class).toInstant();
        var latest = jdbc.queryForObject("SELECT max(starts_at) FROM showtime", Timestamp.class).toInstant();

        assertThat(earliest).isEqualTo(Instant.parse("2026-11-03T19:00:00Z"));
        assertThat(latest).isEqualTo(Instant.parse("2027-01-31T22:30:00Z"));
    }

    @Test
    void everySampleShowtimeHasInventoryForEverySeatOfItsScreen() {
        long mismatched = count("""
                SELECT count(*) FROM showtime st
                WHERE (SELECT count(*) FROM show_seat ss WHERE ss.showtime_id = st.id)
                   <> (SELECT count(*) FROM seat se WHERE se.screen_id = st.screen_id)
                """);

        assertThat(mismatched).isZero();
    }

    @Test
    void trigramSearchToleratesTypos() {
        var titles = jdbc.queryForList("SELECT title FROM movie WHERE title % 'Intersteller'", String.class);

        assertThat(titles).contains("Interstellar");
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE indexname IN "
                + "('movie_title_trgm_idx', 'theater_name_trgm_idx')")).isEqualTo(2);
    }

    @Test
    void bookingHistoryIsIndexedByCustomerNewestFirst() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'booking_customer_history_idx'", String.class);

        assertThat(definition).contains("(customer_id, created_at DESC, id DESC)");
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE indexname = 'booking_customer_idx'")).isZero();
    }

    @Test
    void unpublishedOutboxRowsAreIndexedForTheRelay() {
        String due = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'outbox_event_due_idx'", String.class);
        String history = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'notification_inbox_customer_history_idx'",
                String.class);

        assertThat(due).contains("WHERE (published_at IS NULL)");
        assertThat(history).contains("(customer_id, created_at DESC, id DESC)");
    }

    @Test
    void theSameOutboxEventCannotBeDeliveredTwiceToTheInbox() {
        long customer = insertUser("cust-inbox", "CUSTOMER");
        UUID event = UUID.randomUUID();
        insertInbox(event, customer, "BOOKREF1");

        assertViolation(() -> insertInbox(event, customer, "BOOKREF1"),
                UNIQUE_VIOLATION, "notification_inbox_event_uk");
    }

    @Test
    void seededCitiesAreInCentralTime() {
        var zones = jdbc.queryForList("SELECT DISTINCT time_zone FROM city", String.class);

        assertThat(zones).containsExactly("America/Chicago");
    }

    @Test
    void aCityMustStateAnIanaTimeZone() {
        assertViolation(() -> jdbc.update("INSERT INTO city (name, state_code) VALUES ('El Paso', 'TX')"),
                "23502", "time_zone");
    }

    @Test
    void aCityTimeZoneMustLookLikeAnIanaZoneId() {
        assertViolation(() -> jdbc.update(
                        "INSERT INTO city (name, state_code, time_zone) VALUES ('El Paso', 'TX', 'CST')"),
                CHECK_VIOLATION, "city_time_zone_ck");
    }

    // --- booked seats -----------------------------------------------------------------------

    @Test
    void aSeatCannotBeBookedTwiceForTheSameShowtime() {
        long customer = insertUser("cust-a", "CUSTOMER");
        long showtime = firstShowtime();
        long seat = firstSeatOf(showtime);
        UUID first = insertConfirmedBooking(customer, showtime, "BOOKREF1", "key-1");
        UUID second = insertConfirmedBooking(customer, showtime, "BOOKREF2", "key-2");
        insertBookingSeat(first, showtime, seat);

        assertViolation(() -> insertBookingSeat(second, showtime, seat),
                UNIQUE_VIOLATION, "booking_seat_showtime_seat_uk");
    }

    @Test
    void theSameSeatCanBeBookedForDifferentShowtimes() {
        long customer = insertUser("cust-a", "CUSTOMER");
        long showtime = firstShowtime();
        long nextShowtimeSameScreen = jdbc.queryForObject("""
                SELECT id FROM showtime
                WHERE screen_id = (SELECT screen_id FROM showtime WHERE id = ?) AND id <> ?
                ORDER BY starts_at LIMIT 1
                """, Long.class, showtime, showtime);
        long seat = firstSeatOf(showtime);
        UUID first = insertConfirmedBooking(customer, showtime, "BOOKREF1", "key-1");
        UUID second = insertConfirmedBooking(customer, nextShowtimeSameScreen, "BOOKREF2", "key-2");
        insertBookingSeat(first, showtime, seat);

        assertThatCode(() -> insertBookingSeat(second, nextShowtimeSameScreen, seat)).doesNotThrowAnyException();
    }

    @Test
    void aBookingSeatMustExistInTheShowtimeInventory() {
        long customer = insertUser("cust-a", "CUSTOMER");
        long showtime = firstShowtime();
        UUID booking = insertConfirmedBooking(customer, showtime, "BOOKREF1", "key-1");
        long seatOfOtherScreen = jdbc.queryForObject(
                "SELECT min(id) FROM seat WHERE screen_id <> (SELECT screen_id FROM showtime WHERE id = ?)",
                Long.class, showtime);

        assertViolation(() -> insertBookingSeat(booking, showtime, seatOfOtherScreen),
                FOREIGN_KEY_VIOLATION, "booking_seat_show_seat_fk");
    }

    // --- seat holds -------------------------------------------------------------------------

    @Test
    void aCustomerCannotHaveTwoActiveHoldsForTheSameShowtime() {
        long customer = insertUser("cust-a", "CUSTOMER");
        long showtime = firstShowtime();
        insertHold(customer, showtime, "ACTIVE");

        assertViolation(() -> insertHold(customer, showtime, "ACTIVE"),
                UNIQUE_VIOLATION, "seat_hold_one_active_per_customer_showtime_uk");
    }

    @Test
    void anExpiredHoldMarkedExpiredCanBeReplacedByANewActiveHold() {
        long customer = insertUser("cust-a", "CUSTOMER");
        long showtime = firstShowtime();
        UUID old = insertHold(customer, showtime, "ACTIVE");
        jdbc.update("UPDATE seat_hold SET status = 'EXPIRED' WHERE id = ?", old);
        insertHold(customer, showtime, "RELEASED");
        insertHold(customer, showtime, "CONVERTED");

        assertThatCode(() -> insertHold(customer, showtime, "ACTIVE")).doesNotThrowAnyException();
        assertThat(count("SELECT count(*) FROM seat_hold WHERE customer_id = " + customer)).isEqualTo(4);
    }

    @Test
    void differentCustomersCanEachHoldTheSameShowtime() {
        long showtime = firstShowtime();
        insertHold(insertUser("cust-a", "CUSTOMER"), showtime, "ACTIVE");

        assertThatCode(() -> insertHold(insertUser("cust-b", "CUSTOMER"), showtime, "ACTIVE"))
                .doesNotThrowAnyException();
    }

    // --- show_seat row shape and ownership --------------------------------------------------

    @Test
    void aHeldSeatMustReferenceAHold() {
        long showtime = firstShowtime();
        long seat = firstSeatOf(showtime);

        assertViolation(() -> jdbc.update(
                        "UPDATE show_seat SET status = 'HELD' WHERE showtime_id = ? AND seat_id = ?", showtime, seat),
                CHECK_VIOLATION, "show_seat_status_shape_ck");
    }

    @Test
    void aBookedSeatCannotKeepHoldFields() {
        long customer = insertUser("cust-a", "CUSTOMER");
        long showtime = firstShowtime();
        long seat = firstSeatOf(showtime);
        UUID hold = insertHold(customer, showtime, "ACTIVE");

        assertViolation(() -> jdbc.update("""
                        UPDATE show_seat SET status = 'BOOKED', hold_id = ?, hold_expires_at = ?
                        WHERE showtime_id = ? AND seat_id = ?
                        """, hold, ts(T0.plus(8, ChronoUnit.MINUTES)), showtime, seat),
                CHECK_VIOLATION, "show_seat_status_shape_ck");
    }

    @Test
    void aSeatCanOnlyBeHeldByAHoldForTheSameShowtime() {
        long customer = insertUser("cust-a", "CUSTOMER");
        long showtime = firstShowtime();
        long otherShowtime = jdbc.queryForObject("SELECT max(id) FROM showtime", Long.class);
        UUID holdForOtherShowtime = insertHold(customer, otherShowtime, "ACTIVE");
        long seat = firstSeatOf(showtime);

        assertViolation(() -> jdbc.update("""
                        UPDATE show_seat SET status = 'HELD', hold_id = ?, hold_expires_at = ?
                        WHERE showtime_id = ? AND seat_id = ?
                        """, holdForOtherShowtime, ts(T0.plus(8, ChronoUnit.MINUTES)), showtime, seat),
                FOREIGN_KEY_VIOLATION, "show_seat_hold_fk");
    }

    @Test
    void inventoryCannotContainASeatFromAnotherScreen() {
        long showtime = firstShowtime();
        long showtimeScreen = jdbc.queryForObject("SELECT screen_id FROM showtime WHERE id = ?", Long.class, showtime);
        long seatOfOtherScreen = jdbc.queryForObject(
                "SELECT min(id) FROM seat WHERE screen_id <> ?", Long.class, showtimeScreen);

        assertViolation(() -> jdbc.update("""
                        INSERT INTO show_seat (showtime_id, seat_id, screen_id, status, price)
                        VALUES (?, ?, ?, 'AVAILABLE', 12.50)
                        """, showtime, seatOfOtherScreen, showtimeScreen),
                FOREIGN_KEY_VIOLATION, "show_seat_seat_fk");
    }

    @Test
    void inventoryCannotReferenceAMissingShowtime() {
        long seat = jdbc.queryForObject("SELECT min(id) FROM seat", Long.class);
        long screen = jdbc.queryForObject("SELECT screen_id FROM seat WHERE id = ?", Long.class, seat);

        assertViolation(() -> jdbc.update("""
                        INSERT INTO show_seat (showtime_id, seat_id, screen_id, status, price)
                        VALUES (-1, ?, ?, 'AVAILABLE', 12.50)
                        """, seat, screen),
                FOREIGN_KEY_VIOLATION, "show_seat_showtime_fk");
    }

    // --- payments ---------------------------------------------------------------------------

    @Test
    void anIdempotencyKeyMapsToExactlyOneAttempt() {
        long customer = insertUser("cust-a", "CUSTOMER");
        UUID holdA = insertHold(customer, firstShowtime(), "ACTIVE");
        UUID holdB = insertHold(customer, jdbc.queryForObject("SELECT max(id) FROM showtime", Long.class), "ACTIVE");
        insertAttempt(holdA, "same-key", "DECLINED", "PROVIDER_DECLINED");

        assertViolation(() -> insertAttempt(holdB, "same-key", "PENDING", null),
                UNIQUE_VIOLATION, "payment_attempt_idempotency_key_uk");
    }

    @Test
    void aHoldCannotHaveTwoInFlightAttempts() {
        UUID hold = insertHold(insertUser("cust-a", "CUSTOMER"), firstShowtime(), "ACTIVE");
        insertAttempt(hold, "key-1", "PENDING", null);

        assertViolation(() -> insertAttempt(hold, "key-2", "UNKNOWN", null),
                UNIQUE_VIOLATION, "payment_attempt_one_in_flight_per_hold_uk");
    }

    @Test
    void aRetryAfterADeclineIsAllowed() {
        UUID hold = insertHold(insertUser("cust-a", "CUSTOMER"), firstShowtime(), "ACTIVE");
        insertAttempt(hold, "key-1", "DECLINED", "PROVIDER_DECLINED");

        assertThatCode(() -> insertAttempt(hold, "key-2", "PENDING", null)).doesNotThrowAnyException();
    }

    @Test
    void aDeclineMustRecordAReason() {
        UUID hold = insertHold(insertUser("cust-a", "CUSTOMER"), firstShowtime(), "ACTIVE");

        assertViolation(() -> insertAttempt(hold, "key-1", "DECLINED", null),
                CHECK_VIOLATION, "payment_attempt_failure_reason_ck");
    }

    @Test
    void anUnknownAttemptCannotCarryAFailureReason() {
        UUID hold = insertHold(insertUser("cust-a", "CUSTOMER"), firstShowtime(), "ACTIVE");

        assertViolation(() -> insertAttempt(hold, "key-1", "UNKNOWN", "PROVIDER_DECLINED"),
                CHECK_VIOLATION, "payment_attempt_failure_reason_ck");
    }

    @Test
    void theSimulatedProviderChargesAtMostOncePerKey() {
        String insert = """
                INSERT INTO simulated_provider_charge (idempotency_key, amount, status, created_at, updated_at)
                VALUES ('key-1', 25.00, 'CAPTURED', ?, ?)
                """;
        jdbc.update(insert, ts(T0), ts(T0));

        assertViolation(() -> jdbc.update(insert, ts(T0), ts(T0)),
                UNIQUE_VIOLATION, "simulated_provider_charge_pk");
    }

    // --- bookings ---------------------------------------------------------------------------

    @Test
    void aHoldConvertsToAtMostOneBooking() {
        long customer = insertUser("cust-a", "CUSTOMER");
        long showtime = firstShowtime();
        UUID hold = insertHold(customer, showtime, "CONVERTED");
        UUID attempt1 = insertAttempt(hold, "key-1", "SUCCEEDED", null);
        UUID attempt2 = insertAttempt(hold, "key-2", "SUCCEEDED", null);
        insertBooking(customer, showtime, hold, attempt1, "BOOKREF1");

        assertViolation(() -> insertBooking(customer, showtime, hold, attempt2, "BOOKREF2"),
                UNIQUE_VIOLATION, "booking_hold_uk");
    }

    @Test
    void aBookingsPaymentMustBelongToItsHold() {
        long customer = insertUser("cust-a", "CUSTOMER");
        long showtime = firstShowtime();
        UUID hold = insertHold(customer, showtime, "CONVERTED");
        UUID otherHold = insertHold(customer, showtime, "EXPIRED");
        UUID attemptOfOtherHold = insertAttempt(otherHold, "key-1", "SUCCEEDED", null);

        assertViolation(() -> insertBooking(customer, showtime, hold, attemptOfOtherHold, "BOOKREF1"),
                FOREIGN_KEY_VIOLATION, "booking_payment_attempt_fk");
    }

    @Test
    void aBookingMustBelongToTheHoldsCustomer() {
        long owner = insertUser("cust-a", "CUSTOMER");
        long someoneElse = insertUser("cust-b", "CUSTOMER");
        long showtime = firstShowtime();
        UUID hold = insertHold(owner, showtime, "CONVERTED");
        UUID attempt = insertAttempt(hold, "key-1", "SUCCEEDED", null);

        assertViolation(() -> insertBooking(someoneElse, showtime, hold, attempt, "BOOKREF1"),
                FOREIGN_KEY_VIOLATION, "booking_hold_fk");
    }

    // --- theater admins and showtimes -------------------------------------------------------

    @Test
    void onlyTheaterAdminsCanBeAssignedToTheaters() {
        long theater = jdbc.queryForObject("SELECT min(id) FROM theater", Long.class);
        long admin = insertUser("admin-a", "THEATER_ADMIN");
        jdbc.update("INSERT INTO theater_admin_assignment (user_id, theater_id) VALUES (?, ?)", admin, theater);
        long customer = insertUser("cust-a", "CUSTOMER");

        assertViolation(() -> jdbc.update(
                        "INSERT INTO theater_admin_assignment (user_id, theater_id) VALUES (?, ?)", customer, theater),
                FOREIGN_KEY_VIOLATION, "theater_admin_assignment_user_fk");
    }

    @Test
    void scheduledShowtimesCannotOverlapOnTheSameScreen() {
        long existing = firstShowtime();

        assertViolation(() -> insertShowtimeOverlapping(existing, "SCHEDULED"),
                EXCLUSION_VIOLATION, "showtime_no_overlap_ex");
    }

    @Test
    void aCancelledShowtimeDoesNotBlockItsSlot() {
        long existing = firstShowtime();

        assertThatCode(() -> insertShowtimeOverlapping(existing, "CANCELLED")).doesNotThrowAnyException();
    }

    // --- helpers ----------------------------------------------------------------------------

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private long firstShowtime() {
        return jdbc.queryForObject("SELECT id FROM showtime ORDER BY starts_at, id LIMIT 1", Long.class);
    }

    private long firstSeatOf(long showtime) {
        return jdbc.queryForObject(
                "SELECT min(seat_id) FROM show_seat WHERE showtime_id = ?", Long.class, showtime);
    }

    private long insertUser(String username, String role) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (username, password_hash, role, display_name, created_at)
                VALUES (?, 'not-a-real-hash', ?, ?, ?) RETURNING id
                """, Long.class, username, role, username, ts(T0));
    }

    private UUID insertHold(long customer, long showtime, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO seat_hold (id, customer_id, showtime_id, status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, customer, showtime, status, ts(T0.plus(8, ChronoUnit.MINUTES)), ts(T0));
        return id;
    }

    private UUID insertAttempt(UUID hold, String key, String status, String failureReason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_attempt
                    (id, hold_id, idempotency_key, scenario, amount, status, failure_reason, created_at, updated_at)
                VALUES (?, ?, ?, 'SUCCESS', 25.00, ?, ?, ?, ?)
                """, id, hold, key, status, failureReason, ts(T0), ts(T0));
        return id;
    }

    private UUID insertBooking(long customer, long showtime, UUID hold, UUID attempt, String reference) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO booking
                    (id, reference, customer_id, showtime_id, hold_id, payment_attempt_id, total_amount, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 25.00, 'CONFIRMED', ?)
                """, id, reference, customer, showtime, hold, attempt, ts(T0));
        return id;
    }

    private UUID insertConfirmedBooking(long customer, long showtime, String reference, String key) {
        UUID hold = insertHold(customer, showtime, "CONVERTED");
        UUID attempt = insertAttempt(hold, key, "SUCCEEDED", null);
        return insertBooking(customer, showtime, hold, attempt, reference);
    }

    private void insertInbox(UUID eventId, long customer, String reference) {
        jdbc.update("""
                INSERT INTO notification_inbox
                    (id, event_id, customer_id, booking_id, event_type, payload, created_at)
                VALUES (?, ?, ?, ?, 'BookingConfirmed', CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), eventId, customer, UUID.randomUUID(),
                "{\"reference\":\"" + reference + "\",\"customerId\":" + customer + "}", ts(T0));
    }

    private void insertBookingSeat(UUID booking, long showtime, long seat) {
        jdbc.update("INSERT INTO booking_seat (booking_id, showtime_id, seat_id) VALUES (?, ?, ?)",
                booking, showtime, seat);
    }

    private void insertShowtimeOverlapping(long existing, String status) {
        jdbc.update("""
                INSERT INTO showtime (screen_id, movie_id, language_code, starts_at, ends_at, status, created_at)
                SELECT screen_id, movie_id, language_code,
                       starts_at + INTERVAL '30 minutes', ends_at + INTERVAL '30 minutes', ?, ?
                FROM showtime WHERE id = ?
                """, status, ts(T0), existing);
    }

    private static void assertViolation(Runnable statement, String sqlState, String constraintName) {
        assertThatThrownBy(statement::run)
                .satisfies(thrown -> {
                    SQLException sqlException = findSqlException(thrown);
                    assertThat(sqlException.getSQLState()).isEqualTo(sqlState);
                    assertThat(sqlException.getMessage()).contains(constraintName);
                });
    }

    private static SQLException findSqlException(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        throw new AssertionError("Expected an SQLException in the cause chain of " + thrown);
    }
}
