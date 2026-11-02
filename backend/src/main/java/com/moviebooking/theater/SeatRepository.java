package com.moviebooking.theater;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScreenIdOrderByRowLabelAscSeatNumberAsc(long screenId);
}
