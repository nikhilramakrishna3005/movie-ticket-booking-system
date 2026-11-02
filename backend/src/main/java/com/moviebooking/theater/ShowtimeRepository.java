package com.moviebooking.theater;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {
}
