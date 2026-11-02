package com.moviebooking.booking;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeat.Key> {
}
