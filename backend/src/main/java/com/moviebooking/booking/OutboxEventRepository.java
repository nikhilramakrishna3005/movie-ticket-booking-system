package com.moviebooking.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
