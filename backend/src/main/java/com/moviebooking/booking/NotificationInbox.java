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

/** Local notification row keyed by outbox event id so replay cannot duplicate delivery. */
@Entity
@Table(name = "notification_inbox")
public class NotificationInbox implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "customer_id", nullable = false)
    private long customerId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew;

    protected NotificationInbox() {
    }

    static NotificationInbox fromEvent(OutboxEvent event, Instant now) {
        NotificationInbox row = new NotificationInbox();
        row.id = UUID.randomUUID();
        row.eventId = event.getId();
        row.customerId = customerId(event.getPayload());
        row.bookingId = event.getAggregateId();
        row.eventType = event.getEventType();
        row.payload = event.getPayload();
        row.createdAt = now;
        row.isNew = true;
        return row;
    }

    static long customerId(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("customerId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("BookingConfirmed payload is missing customerId");
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    UUID getEventId() {
        return eventId;
    }

    long getCustomerId() {
        return customerId;
    }

    UUID getBookingId() {
        return bookingId;
    }

    String getEventType() {
        return eventType;
    }

    Map<String, Object> getPayload() {
        return payload;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }
}
