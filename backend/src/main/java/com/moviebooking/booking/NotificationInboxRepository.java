package com.moviebooking.booking;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationInboxRepository extends JpaRepository<NotificationInbox, UUID> {

    boolean existsByEventId(UUID eventId);

    Page<NotificationInbox> findByCustomerId(long customerId, Pageable pageable);
}
