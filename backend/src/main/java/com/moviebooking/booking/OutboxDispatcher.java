package com.moviebooking.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Polls unpublished outbox rows in bounded batches, hands each to {@link NotificationInboxWriter},
 * then sets {@code published_at} only if this worker's {@code claim_token} still owns the row.
 * Delivery is at-least-once: a crash after the inbox write and before the ack is recovered by
 * replay, which {@code ON CONFLICT (event_id) DO NOTHING} makes a no-op. A lease timeout does
 * not fence in-flight work by itself; the token does.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRelayTransactions relay;
    private final NotificationInboxWriter consumer;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration claimLease;
    private final OutboxBackoff backoff;

    public OutboxDispatcher(OutboxRelayTransactions relay, NotificationInboxWriter consumer, Clock clock,
            @Value("${booking.outbox.batch-size}") int batchSize,
            @Value("${booking.outbox.max-attempts}") int maxAttempts,
            @Value("${booking.outbox.claim-lease}") Duration claimLease,
            @Value("${booking.outbox.backoff-initial}") Duration backoffInitial,
            @Value("${booking.outbox.backoff-max}") Duration backoffMax) {
        this.relay = relay;
        this.consumer = consumer;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.claimLease = claimLease;
        this.backoff = new OutboxBackoff(backoffInitial, backoffMax);
    }

    /** Visible for tests: process every currently due unpublished event, one bounded batch at a time. */
    public int dispatchDue() {
        int delivered = 0;
        int n;
        do {
            n = dispatchBatch();
            delivered += n;
        } while (n == batchSize);
        return delivered;
    }

    public int dispatchBatch() {
        Instant now = now();
        List<OutboxEvent> claimed = relay.claimDue(now, batchSize, maxAttempts, claimLease);
        int delivered = 0;
        for (OutboxEvent event : claimed) {
            try {
                consumer.record(event, now);
                if (relay.markPublished(event.getId(), event.getClaimToken(), now)) {
                    delivered++;
                }
            } catch (RuntimeException ex) {
                log.warn("outbox event {} failed (attempt {}): {}", event.getId(), event.getAttemptCount(),
                        ex.toString());
                relay.recordFailure(event.getId(), event.getClaimToken(), ex.toString(),
                        backoff.nextAttempt(now, event.getAttemptCount()));
            }
        }
        return delivered;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
