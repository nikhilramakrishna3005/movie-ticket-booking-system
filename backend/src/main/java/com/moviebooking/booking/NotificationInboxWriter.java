package com.moviebooking.booking;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Local consumer of {@code BookingConfirmed} events. Writes {@code notification_inbox} keyed by
 * event id so a replay cannot create a second row. Invoked by {@link OutboxDispatcher} after the
 * checkout transaction has committed, never from inside it.
 */
@Service
public class NotificationInboxWriter {

    private final NotificationInboxRepository inbox;
    private final ObjectMapper json;

    public NotificationInboxWriter(NotificationInboxRepository inbox, ObjectMapper json) {
        this.inbox = inbox;
        this.json = json;
    }

    @Transactional
    public void record(OutboxEvent event, Instant now) {
        if (!OutboxEvent.BOOKING_CONFIRMED.equals(event.getEventType())) {
            throw new IllegalArgumentException("unsupported outbox event type: " + event.getEventType());
        }
        NotificationInbox row = NotificationInbox.fromEvent(event, now);
        inbox.insertIgnoreDuplicate(
                row.getId(),
                row.getEventId(),
                row.getCustomerId(),
                row.getBookingId(),
                row.getEventType(),
                payloadJson(row),
                row.getCreatedAt());
    }

    private String payloadJson(NotificationInbox row) {
        try {
            return json.writeValueAsString(row.getPayload());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialize notification payload", ex);
        }
    }
}
