package com.moviebooking.booking;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByPaymentAttemptId(UUID paymentAttemptId);

    Optional<Booking> findByHoldId(UUID holdId);

    /** Served by {@code booking_customer_history_idx} when sorted by created_at and id, both descending. */
    Page<Booking> findByCustomerIdAndStatus(long customerId, String status, Pageable pageable);
}
