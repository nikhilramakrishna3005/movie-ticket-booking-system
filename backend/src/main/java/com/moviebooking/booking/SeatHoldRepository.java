package com.moviebooking.booking;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    /** The partial unique index guarantees at most one ACTIVE hold per customer and showtime. */
    @Query(value = """
            SELECT * FROM seat_hold
            WHERE customer_id = :customerId AND showtime_id = :showtimeId AND status = 'ACTIVE'
            FOR UPDATE""", nativeQuery = true)
    Optional<SeatHold> lockActiveHold(@Param("customerId") long customerId, @Param("showtimeId") long showtimeId);

    @Query(value = "SELECT * FROM seat_hold WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<SeatHold> lockById(@Param("id") UUID id);
}
