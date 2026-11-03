package com.moviebooking.booking;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One sellable seat of one showtime: the authoritative record of who owns it. The setters keep
 * the row shape required by {@code show_seat_status_shape_ck}.
 */
@Entity
@Table(name = "show_seat")
@IdClass(ShowSeat.Key.class)
public class ShowSeat {

    @Id
    @Column(name = "showtime_id")
    private long showtimeId;

    @Id
    @Column(name = "seat_id")
    private long seatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShowSeatStatus status;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(nullable = false)
    private BigDecimal price;

    protected ShowSeat() {
    }

    public long getSeatId() {
        return seatId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    /**
     * Status as customers see it at {@code now}: a hold whose expiry has passed counts as
     * available even though the row still says HELD (lazy expiry, no cleanup job).
     */
    public ShowSeatStatus effectiveStatusAt(Instant now) {
        if (status == ShowSeatStatus.HELD && !holdExpiresAt.isAfter(now)) {
            return ShowSeatStatus.AVAILABLE;
        }
        return status;
    }

    public boolean isHeldBy(UUID hold) {
        return status == ShowSeatStatus.HELD && holdId.equals(hold);
    }

    /** Overwrites any expired hold reference; callers must check availability first. */
    void hold(UUID newHoldId, Instant expiresAt) {
        status = ShowSeatStatus.HELD;
        holdId = newHoldId;
        holdExpiresAt = expiresAt;
    }

    /** Permanent: no code path moves a BOOKED seat to any other status. */
    void book(UUID newBookingId) {
        status = ShowSeatStatus.BOOKED;
        bookingId = newBookingId;
        holdId = null;
        holdExpiresAt = null;
    }

    void release() {
        status = ShowSeatStatus.AVAILABLE;
        holdId = null;
        holdExpiresAt = null;
    }

    public static class Key implements Serializable {

        private long showtimeId;
        private long seatId;

        protected Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.showtimeId == showtimeId && key.seatId == seatId;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(showtimeId) * 31 + Long.hashCode(seatId);
        }
    }
}
