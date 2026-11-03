package com.moviebooking.booking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.security.Role;

/**
 * Reads and resets hold state with plain SQL, so assertions check what PostgreSQL actually
 * stored rather than what the service returned.
 */
public final class HoldFixtures {

    private static final String TEST_USER_PREFIX = "it-customer-";

    private final JdbcTemplate jdbc;

    public HoldFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Restores the seeded state: no holds, payments or bookings, all seats AVAILABLE, no
     * cancelled showtimes, no test users. Deletes follow the foreign keys.
     */
    public void reset() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("UPDATE show_seat SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL,"
                + " booking_id = NULL WHERE status <> 'AVAILABLE'");
        jdbc.update("DELETE FROM booking_seat");
        jdbc.update("DELETE FROM booking");
        jdbc.update("DELETE FROM payment_attempt");
        jdbc.update("DELETE FROM simulated_provider_charge");
        jdbc.update("DELETE FROM seat_hold");
        jdbc.update("UPDATE showtime SET status = 'SCHEDULED' WHERE status = 'CANCELLED'");
        jdbc.update("DELETE FROM app_user WHERE username LIKE ?", TEST_USER_PREFIX + "%");
    }

    /** Scheduled showtimes in start order; all start after {@code TestClockConfiguration.START}. */
    public List<Long> showtimes(int count) {
        return jdbc.queryForList("SELECT id FROM showtime ORDER BY starts_at, id LIMIT ?", Long.class, count);
    }

    public long showtimeAt(String theaterName) {
        return jdbc.queryForObject("""
                SELECT st.id FROM showtime st
                JOIN screen sc ON sc.id = st.screen_id
                JOIN theater t ON t.id = sc.theater_id
                WHERE t.name = ?
                ORDER BY st.starts_at, st.id LIMIT 1""", Long.class, theaterName);
    }

    public String showtimeStatus(long showtimeId) {
        return jdbc.queryForObject("SELECT status FROM showtime WHERE id = ?", String.class, showtimeId);
    }

    public long seatInventory(long showtimeId) {
        return jdbc.queryForObject("SELECT count(*) FROM show_seat WHERE showtime_id = ?", Long.class, showtimeId);
    }

    Instant startsAt(long showtimeId) {
        return jdbc.queryForObject("SELECT starts_at FROM showtime WHERE id = ?", Instant.class, showtimeId);
    }

    public List<Long> seatIds(long showtimeId, int count) {
        return jdbc.queryForList("SELECT seat_id FROM show_seat WHERE showtime_id = ? ORDER BY seat_id LIMIT ?",
                Long.class, showtimeId, count);
    }

    long seatOfAnotherScreen(long showtimeId) {
        return jdbc.queryForObject("""
                SELECT min(se.id) FROM seat se
                WHERE se.screen_id <> (SELECT screen_id FROM showtime WHERE id = ?)""", Long.class, showtimeId);
    }

    long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }

    public AuthenticatedUser principal(String username) {
        return customer(userId(username), username);
    }

    /** Extra customers for many-thread tests; the seeded demo data has only two. */
    List<AuthenticatedUser> createCustomers(int count) {
        List<AuthenticatedUser> customers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String username = TEST_USER_PREFIX + i;
            Long id = jdbc.queryForObject("""
                    INSERT INTO app_user (username, password_hash, role, display_name, created_at)
                    VALUES (?, 'not-a-login-account', 'CUSTOMER', ?, TIMESTAMPTZ '2026-11-02 00:00:00+00')
                    RETURNING id""", Long.class, username, username);
            customers.add(customer(id, username));
        }
        return customers;
    }

    void cancel(long showtimeId) {
        jdbc.update("UPDATE showtime SET status = 'CANCELLED' WHERE id = ?", showtimeId);
    }

    public String holdStatus(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    long holdCount() {
        return jdbc.queryForObject("SELECT count(*) FROM seat_hold", Long.class);
    }

    public long activeHoldCount() {
        return jdbc.queryForObject("SELECT count(*) FROM seat_hold WHERE status = 'ACTIVE'", Long.class);
    }

    long holdCountOf(long customerId) {
        return jdbc.queryForObject("SELECT count(*) FROM seat_hold WHERE customer_id = ?", Long.class, customerId);
    }

    /** Seat ids whose show_seat row is HELD by this hold. */
    public List<Long> seatsHeldBy(UUID holdId) {
        return jdbc.queryForList("SELECT seat_id FROM show_seat WHERE hold_id = ? AND status = 'HELD' ORDER BY seat_id",
                Long.class, holdId);
    }

    /** seat_id -> hold_id for every HELD seat of the showtime. */
    Map<Long, UUID> heldSeats(long showtimeId) {
        Map<Long, UUID> held = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT seat_id, hold_id FROM show_seat WHERE showtime_id = ? AND status = 'HELD'", showtimeId)) {
            held.put((Long) row.get("seat_id"), (UUID) row.get("hold_id"));
        }
        return held;
    }

    public String seatStatus(long showtimeId, long seatId) {
        return jdbc.queryForObject("SELECT status FROM show_seat WHERE showtime_id = ? AND seat_id = ?",
                String.class, showtimeId, seatId);
    }

    public long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    String paymentStatus(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment_attempt WHERE id = ?", String.class, paymentId);
    }

    String failureReason(UUID paymentId) {
        return jdbc.queryForObject("SELECT failure_reason FROM payment_attempt WHERE id = ?", String.class,
                paymentId);
    }

    /** Status of the simulated provider's ledger entry for this key, or null if none exists. */
    String ledgerStatus(String idempotencyKey) {
        return jdbc.queryForList("SELECT status FROM simulated_provider_charge WHERE idempotency_key = ?",
                String.class, idempotencyKey).stream().findFirst().orElse(null);
    }

    /** Seat ids whose show_seat row is BOOKED by this booking. */
    List<Long> seatsBookedBy(UUID bookingId) {
        return jdbc.queryForList(
                "SELECT seat_id FROM show_seat WHERE booking_id = ? AND status = 'BOOKED' ORDER BY seat_id",
                Long.class, bookingId);
    }

    List<Long> bookingSeatIds(UUID bookingId) {
        return jdbc.queryForList("SELECT seat_id FROM booking_seat WHERE booking_id = ? ORDER BY seat_id",
                Long.class, bookingId);
    }

    private static AuthenticatedUser customer(long id, String username) {
        return new AuthenticatedUser(id, username, "unused", username, Role.CUSTOMER, Set.of());
    }
}
