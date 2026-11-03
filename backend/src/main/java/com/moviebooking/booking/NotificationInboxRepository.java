package com.moviebooking.booking;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationInboxRepository extends JpaRepository<NotificationInbox, UUID> {

    Page<NotificationInbox> findByCustomerId(long customerId, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO notification_inbox
                (id, event_id, customer_id, booking_id, event_type, payload, created_at)
            VALUES
                (:id, :eventId, :customerId, :bookingId, :eventType, CAST(:payload AS jsonb), :createdAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(
            @Param("id") UUID id,
            @Param("eventId") UUID eventId,
            @Param("customerId") long customerId,
            @Param("bookingId") UUID bookingId,
            @Param("eventType") String eventType,
            @Param("payload") String payload,
            @Param("createdAt") Instant createdAt);
}
