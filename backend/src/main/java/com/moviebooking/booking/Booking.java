package com.moviebooking.booking;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/** A confirmed purchase of the seats of one hold, paid by one payment attempt. */
@Entity
@Table(name = "booking")
public class Booking implements Persistable<UUID> {

    static final String CONFIRMED = "CONFIRMED";

    // No 0/O or 1/I, so references can be read out loud; matches booking_reference_ck.
    private static final String REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int REFERENCE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    private UUID id;

    @Column(nullable = false)
    private String reference;

    @Column(name = "customer_id", nullable = false)
    private long customerId;

    @Column(name = "showtime_id", nullable = false)
    private long showtimeId;

    @Column(name = "hold_id", nullable = false)
    private UUID holdId;

    @Column(name = "payment_attempt_id", nullable = false)
    private UUID paymentAttemptId;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew;

    protected Booking() {
    }

    static Booking confirm(SeatHold hold, UUID paymentAttemptId, BigDecimal totalAmount, Instant now) {
        Booking booking = new Booking();
        booking.id = UUID.randomUUID();
        booking.reference = newReference();
        booking.customerId = hold.getCustomerId();
        booking.showtimeId = hold.getShowtimeId();
        booking.holdId = hold.getId();
        booking.paymentAttemptId = paymentAttemptId;
        booking.totalAmount = totalAmount;
        booking.status = CONFIRMED;
        booking.createdAt = now;
        booking.isNew = true;
        return booking;
    }

    /**
     * 32^8 (about 10^12) possible values. A collision is caught by booking_reference_uk and
     * fails the confirmation transaction, leaving the payment PENDING for resolution.
     */
    private static String newReference() {
        StringBuilder reference = new StringBuilder(REFERENCE_LENGTH);
        for (int i = 0; i < REFERENCE_LENGTH; i++) {
            reference.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
        }
        return reference.toString();
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }

    public String getReference() {
        return reference;
    }

    public long getCustomerId() {
        return customerId;
    }

    public long getShowtimeId() {
        return showtimeId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
