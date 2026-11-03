package com.moviebooking.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short outbox claim and acknowledgment transactions. The consumer runs between them, outside
 * this bean, so a slow or failing delivery does not hold the claim locks.
 */
@Service
public class OutboxRelayTransactions {

    private final OutboxEventRepository outbox;

    public OutboxRelayTransactions(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional
    public List<OutboxEvent> claimDue(Instant now, int batchSize, int maxAttempts, Duration lease) {
        List<OutboxEvent> due = outbox.lockDue(now, maxAttempts, batchSize);
        for (OutboxEvent event : due) {
            event.claim(now, lease);
        }
        return due;
    }

    @Transactional
    public void markPublished(UUID eventId, Instant now) {
        OutboxEvent event = outbox.findById(eventId).orElseThrow();
        event.markPublished(now);
    }

    @Transactional
    public void recordFailure(UUID eventId, String error, Instant nextAttempt) {
        OutboxEvent event = outbox.findById(eventId).orElseThrow();
        event.recordFailure(error, nextAttempt);
    }
}
