package com.moviebooking.booking;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Locking reads are native SQL so the exact lock clause is visible. PostgreSQL applies FOR UPDATE
 * after the ORDER BY, so rows are locked in ascending seat_id order: the global lock order that
 * prevents deadlocks between overlapping requests.
 */
public interface ShowSeatRepository extends JpaRepository<ShowSeat, ShowSeat.Key> {

    List<ShowSeat> findByShowtimeId(long showtimeId);

    List<ShowSeat> findByHoldIdOrderBySeatId(UUID holdId);

    List<ShowSeat> findByBookingIdOrderBySeatId(UUID bookingId);

    List<ShowSeat> findByBookingIdInOrderBySeatId(Collection<UUID> bookingIds);

    @Query(value = """
            SELECT * FROM show_seat
            WHERE showtime_id = :showtimeId AND seat_id IN (:seatIds)
            ORDER BY seat_id
            FOR UPDATE""", nativeQuery = true)
    List<ShowSeat> lockSeats(@Param("showtimeId") long showtimeId, @Param("seatIds") Collection<Long> seatIds);

    /** The requested seats plus any seats still claimed by {@code staleHoldId}, in one ordered pass. */
    @Query(value = """
            SELECT * FROM show_seat
            WHERE showtime_id = :showtimeId AND (seat_id IN (:seatIds) OR hold_id = :staleHoldId)
            ORDER BY seat_id
            FOR UPDATE""", nativeQuery = true)
    List<ShowSeat> lockSeatsAndStaleHold(@Param("showtimeId") long showtimeId,
            @Param("seatIds") Collection<Long> seatIds, @Param("staleHoldId") UUID staleHoldId);

    @Query(value = """
            SELECT * FROM show_seat
            WHERE showtime_id = :showtimeId AND hold_id = :holdId
            ORDER BY seat_id
            FOR UPDATE""", nativeQuery = true)
    List<ShowSeat> lockHeldBy(@Param("showtimeId") long showtimeId, @Param("holdId") UUID holdId);

    /**
     * Bounds every lock wait in the current transaction (the {@code true} argument makes it
     * transaction-local, like SET LOCAL). A wait that exceeds it fails with SQLSTATE 55P03.
     */
    @Query(value = "SELECT set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("timeout") String timeout);
}
