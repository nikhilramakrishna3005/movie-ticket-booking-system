package com.moviebooking.booking;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * A customer's temporary claim on seats of one showtime. {@code status} can stay ACTIVE after
 * {@code expiresAt} until a transaction touches the hold, so decisions use {@link #isExpiredAt}.
 */
@Entity
@Table(name = "seat_hold")
public class SeatHold implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private long customerId;

    @Column(name = "showtime_id", nullable = false)
    private long showtimeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // The id is assigned in Java, so Spring Data cannot tell new from existing by a null id.
    // Without this, save() would merge (SELECT then INSERT) instead of persisting.
    @Transient
    private boolean isNew;

    protected SeatHold() {
    }

    static SeatHold activate(long customerId, long showtimeId, Instant now, Instant expiresAt) {
        SeatHold hold = new SeatHold();
        hold.id = UUID.randomUUID();
        hold.customerId = customerId;
        hold.showtimeId = showtimeId;
        hold.status = HoldStatus.ACTIVE;
        hold.createdAt = now;
        hold.expiresAt = expiresAt;
        hold.isNew = true;
        return hold;
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

    public long getCustomerId() {
        return customerId;
    }

    public long getShowtimeId() {
        return showtimeId;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * Status as the owner should see it at {@code now}. An ACTIVE row past {@code expiresAt} is
     * EXPIRED even if no writer has touched it yet (lazy expiry). Does not change the stored row.
     */
    public HoldStatus effectiveStatusAt(Instant now) {
        if (status == HoldStatus.ACTIVE && isExpiredAt(now)) {
            return HoldStatus.EXPIRED;
        }
        return status;
    }

    void expire() {
        requireActive();
        status = HoldStatus.EXPIRED;
    }

    void release() {
        requireActive();
        status = HoldStatus.RELEASED;
    }

    void convert() {
        requireActive();
        status = HoldStatus.CONVERTED;
    }

    private void requireActive() {
        if (status != HoldStatus.ACTIVE) {
            throw new IllegalStateException("Hold " + id + " is " + status + ", not ACTIVE");
        }
    }
}
