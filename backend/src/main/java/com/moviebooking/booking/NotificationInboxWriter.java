package com.moviebooking.booking;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local consumer of {@code BookingConfirmed} events. Writes {@code notification_inbox} keyed by
 * event id so a replay cannot create a second row. Invoked by {@link OutboxDispatcher} after the
 * checkout transaction has committed, never from inside it.
 */
@Service
public class NotificationInboxWriter {

    private final NotificationInboxRepository inbox;

    public NotificationInboxWriter(NotificationInboxRepository inbox) {
        this.inbox = inbox;
    }

    @Transactional
    public void record(OutboxEvent event, Instant now) {
        if (!OutboxEvent.BOOKING_CONFIRMED.equals(event.getEventType())) {
            throw new IllegalArgumentException("unsupported outbox event type: " + event.getEventType());
        }
        if (inbox.existsByEventId(event.getId())) {
            return;
        }
        try {
            inbox.saveAndFlush(NotificationInbox.fromEvent(event, now));
        } catch (DataIntegrityViolationException duplicate) {
            // Concurrent replay of the same event_id; the unique constraint is the backstop.
        }
    }
}
