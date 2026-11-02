package com.moviebooking.booking;

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
 * Transactional outbox row, written in the same transaction as the change it announces. Nothing
 * publishes these yet (the relay is on the cut list); published_at stays null.
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
        event.isNew = true;
        return event;
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
}
