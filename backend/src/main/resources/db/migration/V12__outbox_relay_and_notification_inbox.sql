-- Task 13: claim/retry columns for the existing outbox, plus a local idempotent inbox.
-- published_at (V5) remains the delivery acknowledgment. Kafka is still not used (ADR 0002).

ALTER TABLE outbox_event
    ADD COLUMN claimed_until timestamptz,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN last_error text;

UPDATE outbox_event SET next_attempt_at = created_at WHERE next_attempt_at IS NULL;

ALTER TABLE outbox_event
    ALTER COLUMN next_attempt_at SET NOT NULL,
    ADD CONSTRAINT outbox_event_attempt_count_ck CHECK (attempt_count >= 0);

CREATE INDEX outbox_event_due_idx
    ON outbox_event (next_attempt_at, created_at, id)
    WHERE published_at IS NULL;

-- Local consumer destination. Unique event_id is the idempotency key for at-least-once replay.
CREATE TABLE notification_inbox (
    id          uuid        CONSTRAINT notification_inbox_pk PRIMARY KEY,
    event_id    uuid        NOT NULL CONSTRAINT notification_inbox_event_uk UNIQUE,
    customer_id bigint      NOT NULL CONSTRAINT notification_inbox_customer_fk REFERENCES app_user (id),
    booking_id  uuid        NOT NULL,
    event_type  text        NOT NULL,
    payload     jsonb       NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT notification_inbox_event_type_ck CHECK (btrim(event_type) <> ''),
    CONSTRAINT notification_inbox_payload_ck CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX notification_inbox_customer_history_idx
    ON notification_inbox (customer_id, created_at DESC, id DESC);
