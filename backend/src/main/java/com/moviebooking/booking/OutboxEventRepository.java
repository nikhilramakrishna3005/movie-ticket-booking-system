package com.moviebooking.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Unpublished due rows, skipped if another worker already holds them. Must run inside a
     * transaction so the row locks last until claim fields are flushed.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL
              AND next_attempt_at <= :now
              AND (claimed_until IS NULL OR claimed_until <= :now)
              AND attempt_count < :maxAttempts
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockDue(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
            SET published_at = :now,
                claimed_until = NULL,
                last_error = NULL,
                claim_token = NULL
            WHERE id = :id
              AND claim_token = :claimToken
              AND published_at IS NULL
            """, nativeQuery = true)
    int acknowledgeIfOwner(@Param("id") UUID id, @Param("claimToken") UUID claimToken, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
            SET claimed_until = NULL,
                last_error = :error,
                next_attempt_at = :nextAttempt,
                claim_token = NULL
            WHERE id = :id
              AND claim_token = :claimToken
              AND published_at IS NULL
            """, nativeQuery = true)
    int recordFailureIfOwner(@Param("id") UUID id, @Param("claimToken") UUID claimToken,
            @Param("error") String error, @Param("nextAttempt") Instant nextAttempt);
}
