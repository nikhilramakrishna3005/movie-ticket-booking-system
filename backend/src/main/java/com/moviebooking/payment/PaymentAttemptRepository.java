package com.moviebooking.payment;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentAttempt> findFirstByHoldIdAndStatusIn(UUID holdId, Collection<PaymentStatus> statuses);

    long countByHoldId(UUID holdId);

    /** Reads only the immutable hold id, without loading the entity into the persistence context. */
    @Query("SELECT a.holdId FROM PaymentAttempt a WHERE a.id = :id")
    Optional<UUID> findHoldIdById(@Param("id") UUID id);

    /**
     * In-flight attempts of holds for this showtime. Joins {@code seat_hold} only to filter by
     * showtime; cancellation decides from this count and does not change payment rows.
     */
    @Query(value = """
            SELECT count(*) FROM payment_attempt pa
            JOIN seat_hold sh ON sh.id = pa.hold_id
            WHERE sh.showtime_id = :showtimeId AND pa.status IN ('PENDING', 'UNKNOWN')""", nativeQuery = true)
    long countInFlightForShowtime(@Param("showtimeId") long showtimeId);
}
