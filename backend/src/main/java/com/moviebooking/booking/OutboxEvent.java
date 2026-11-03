package com.moviebooking.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Transactional outbox row, written in the same transaction as the change it announces.
 * {@code published_at} is set only after the local consumer has persisted its inbox row
 * (at-least-once; ADR 0002).
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent implements Persistable<UUID> {

    static final String BOOKING_CONFIRMED = "BookingConfirmed";

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "claimed_until")
    private Instant claimedUntil;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Transient
    private boolean isNew;

    protected OutboxEvent() {
    }

    static OutboxEvent bookingConfirmed(UUID bookingId, Map<String, Object> payload, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.aggregateType = "Booking";
        event.aggregateId = bookingId;
        event.eventType = BOOKING_CONFIRMED;
        event.payload = payload;
        event.createdAt = now;
        event.nextAttemptAt = now;
        event.attemptCount = 0;
        event.isNew = true;
        return event;
    }

    void claim(Instant now, Duration lease) {
        claimedUntil = now.plus(lease);
        attemptCount++;
        claimToken = UUID.randomUUID();
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    UUID getAggregateId() {
        return aggregateId;
    }

    String getEventType() {
        return eventType;
    }

    Map<String, Object> getPayload() {
        return payload;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    Instant getClaimedUntil() {
        return claimedUntil;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    int getAttemptCount() {
        return attemptCount;
    }

    String getLastError() {
        return lastError;
    }

    UUID getClaimToken() {
        return claimToken;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }
}
