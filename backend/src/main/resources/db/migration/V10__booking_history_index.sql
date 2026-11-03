-- Customer booking history (GET /api/bookings) pages a customer's bookings newest first, with id
-- as the tie-breaker so page boundaries are stable. This index serves both the page query and its
-- count. booking_customer_idx (customer_id) is a prefix of it, so it is dropped as redundant.
CREATE INDEX booking_customer_history_idx ON booking (customer_id, created_at DESC, id DESC);

DROP INDEX booking_customer_idx;
