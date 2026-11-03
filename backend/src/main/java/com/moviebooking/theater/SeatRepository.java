package com.moviebooking.theater;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScreenIdOrderByRowLabelAscSeatNumberAsc(long screenId);

    List<Seat> findByScreenIdIn(Collection<Long> screenIds);

    @Modifying
    @Query(value = """
            INSERT INTO seat (screen_id, row_label, seat_number, category)
            SELECT :screenId, :rowLabel, n, :category FROM generate_series(1, :seatCount) AS n""", nativeQuery = true)
    int insertRow(@Param("screenId") long screenId, @Param("rowLabel") String rowLabel,
            @Param("seatCount") int seatCount, @Param("category") String category);

    /**
     * Fails with a foreign-key violation if any showtime's inventory still references a seat, so
     * seats that were ever offered for a showtime cannot be removed even if a caller forgets to check.
     */
    @Modifying
    @Query(value = "DELETE FROM seat WHERE screen_id = :screenId", nativeQuery = true)
    int deleteByScreen(@Param("screenId") long screenId);
}
