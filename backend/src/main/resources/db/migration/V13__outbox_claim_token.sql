-- Task 13 review: fence outbox ack/failure with a per-claim token so an expired worker
-- cannot mark published or record failure after another worker has reclaimed the row.
-- published_at (V5) remains the delivery acknowledgment.

ALTER TABLE outbox_event
    ADD COLUMN claim_token uuid;
