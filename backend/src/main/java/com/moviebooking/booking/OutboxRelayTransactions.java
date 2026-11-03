package com.moviebooking.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short outbox claim and acknowledgment transactions. The consumer runs between them, outside
 * this bean, so a slow or failing delivery does not hold the claim locks. Ack and failure
 * updates are fenced by {@code claim_token}: a worker whose lease expired cannot mutate a
 * row another worker has reclaimed.
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
    public boolean markPublished(UUID eventId, UUID claimToken, Instant now) {
        return outbox.acknowledgeIfOwner(eventId, claimToken, now) == 1;
    }

    @Transactional
    public boolean recordFailure(UUID eventId, UUID claimToken, String error, Instant nextAttempt) {
        String trimmed = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
        return outbox.recordFailureIfOwner(eventId, claimToken, trimmed, nextAttempt) == 1;
    }
}
