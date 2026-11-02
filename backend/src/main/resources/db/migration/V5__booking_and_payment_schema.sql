-- Owned by the booking package (seat_hold, booking, show_seat, booking_seat, outbox_event) and
-- the payment package (payment_attempt, simulated_provider_charge). They share one migration
-- because their foreign keys reference each other: payment_attempt -> seat_hold and
-- booking -> payment_attempt.
--
-- Timestamps have no defaults: the application sets them from the injected UTC Clock
-- (docs/architecture.md §7).

CREATE TABLE seat_hold (
    id          uuid        CONSTRAINT seat_hold_pk PRIMARY KEY,
    customer_id bigint      NOT NULL CONSTRAINT seat_hold_customer_fk REFERENCES app_user (id),
    showtime_id bigint      NOT NULL CONSTRAINT seat_hold_showtime_fk REFERENCES showtime (id),
    status      text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL,
    -- Targets of composite foreign keys from show_seat and booking.
    CONSTRAINT seat_hold_id_showtime_uk UNIQUE (id, showtime_id),
    CONSTRAINT seat_hold_id_customer_showtime_uk UNIQUE (id, customer_id, showtime_id),
    CONSTRAINT seat_hold_status_ck CHECK (status IN ('ACTIVE', 'CONVERTED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT seat_hold_expiry_ck CHECK (expires_at > created_at)
);

-- One ACTIVE hold per customer per showtime (docs/architecture.md §5.5). The predicate is on
-- status only; a time-based predicate is impossible because index predicates must be immutable.
-- Expiry is business logic: the hold-creation transaction marks an expired ACTIVE hold EXPIRED
-- before inserting the replacement, so an expired hold never blocks a new one.
CREATE UNIQUE INDEX seat_hold_one_active_per_customer_showtime_uk
    ON seat_hold (customer_id, showtime_id)
    WHERE status = 'ACTIVE';

CREATE INDEX seat_hold_showtime_idx ON seat_hold (showtime_id);

CREATE TABLE payment_attempt (
    id              uuid          CONSTRAINT payment_attempt_pk PRIMARY KEY,
    hold_id         uuid          NOT NULL CONSTRAINT payment_attempt_hold_fk REFERENCES seat_hold (id),
    idempotency_key text          NOT NULL,
    scenario        text          NOT NULL,
    amount          numeric(10, 2) NOT NULL,
    status          text          NOT NULL,
    failure_reason  text,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    CONSTRAINT payment_attempt_idempotency_key_uk UNIQUE (idempotency_key),
    -- Target of booking's composite foreign key: a booking's payment must belong to its hold.
    CONSTRAINT payment_attempt_id_hold_uk UNIQUE (id, hold_id),
    CONSTRAINT payment_attempt_idempotency_key_ck CHECK (char_length(idempotency_key) BETWEEN 1 AND 100),
    CONSTRAINT payment_attempt_scenario_ck CHECK (scenario IN ('SUCCESS', 'DECLINE', 'TIMEOUT')),
    CONSTRAINT payment_attempt_amount_ck CHECK (amount > 0),
    CONSTRAINT payment_attempt_status_ck
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'DECLINED', 'UNKNOWN', 'VOIDED')),
    -- A decline always records why; UNKNOWN is never silently turned into a reasonless decline.
    -- The explicit IS NOT NULL matters: without it a NULL reason makes the CHECK evaluate to
    -- NULL, which PostgreSQL treats as passing.
    CONSTRAINT payment_attempt_failure_reason_ck CHECK (
        (status = 'DECLINED' AND failure_reason IS NOT NULL
            AND failure_reason IN ('PROVIDER_DECLINED', 'NOT_RECEIVED'))
        OR (status <> 'DECLINED' AND failure_reason IS NULL)
    ),
    CONSTRAINT payment_attempt_updated_ck CHECK (updated_at >= created_at)
);

-- At most one in-flight attempt per hold (docs/architecture.md §6.5 rule 3).
CREATE UNIQUE INDEX payment_attempt_one_in_flight_per_hold_uk
    ON payment_attempt (hold_id)
    WHERE status IN ('PENDING', 'UNKNOWN');

CREATE INDEX payment_attempt_hold_idx ON payment_attempt (hold_id);

-- Ledger of the simulated external provider. It deliberately has no foreign key to
-- payment_attempt: it models a separate system whose records exist independently.
CREATE TABLE simulated_provider_charge (
    idempotency_key text           CONSTRAINT simulated_provider_charge_pk PRIMARY KEY,
    amount          numeric(10, 2) NOT NULL,
    status          text           NOT NULL,
    created_at      timestamptz    NOT NULL,
    updated_at      timestamptz    NOT NULL,
    CONSTRAINT simulated_provider_charge_amount_ck CHECK (amount > 0),
    CONSTRAINT simulated_provider_charge_status_ck CHECK (status IN ('CAPTURED', 'DECLINED', 'VOIDED')),
    CONSTRAINT simulated_provider_charge_updated_ck CHECK (updated_at >= created_at)
);

CREATE TABLE booking (
    id                 uuid           CONSTRAINT booking_pk PRIMARY KEY,
    reference          text           NOT NULL,
    customer_id        bigint         NOT NULL,
    showtime_id        bigint         NOT NULL,
    hold_id            uuid           NOT NULL,
    payment_attempt_id uuid           NOT NULL,
    total_amount       numeric(10, 2) NOT NULL,
    status             text           NOT NULL,
    created_at         timestamptz    NOT NULL,
    CONSTRAINT booking_reference_uk UNIQUE (reference),
    -- One hold and one payment produce at most one booking.
    CONSTRAINT booking_hold_uk UNIQUE (hold_id),
    CONSTRAINT booking_payment_attempt_uk UNIQUE (payment_attempt_id),
    -- Target of composite foreign keys from show_seat and booking_seat.
    CONSTRAINT booking_id_showtime_uk UNIQUE (id, showtime_id),
    -- The booking's customer and showtime must be those of the hold it converts.
    CONSTRAINT booking_hold_fk FOREIGN KEY (hold_id, customer_id, showtime_id)
        REFERENCES seat_hold (id, customer_id, showtime_id),
    CONSTRAINT booking_payment_attempt_fk FOREIGN KEY (payment_attempt_id, hold_id)
        REFERENCES payment_attempt (id, hold_id),
    CONSTRAINT booking_reference_ck CHECK (reference ~ '^[A-Z0-9]{6,12}$'),
    CONSTRAINT booking_total_amount_ck CHECK (total_amount > 0),
    CONSTRAINT booking_status_ck CHECK (status = 'CONFIRMED')
);

CREATE INDEX booking_customer_idx ON booking (customer_id);
CREATE INDEX booking_showtime_idx ON booking (showtime_id);

-- Per-showtime seat inventory: the authoritative record of who owns each seat.
-- screen_id is carried so that both composite foreign keys below can prove the seat belongs
-- to the showtime's screen.
CREATE TABLE show_seat (
    showtime_id     bigint         NOT NULL,
    seat_id         bigint         NOT NULL,
    screen_id       bigint         NOT NULL,
    status          text           NOT NULL,
    hold_id         uuid,
    hold_expires_at timestamptz,
    booking_id      uuid,
    price           numeric(10, 2) NOT NULL,
    CONSTRAINT show_seat_pk PRIMARY KEY (showtime_id, seat_id),
    CONSTRAINT show_seat_showtime_fk FOREIGN KEY (showtime_id, screen_id) REFERENCES showtime (id, screen_id),
    CONSTRAINT show_seat_seat_fk FOREIGN KEY (seat_id, screen_id) REFERENCES seat (id, screen_id),
    CONSTRAINT show_seat_hold_fk FOREIGN KEY (hold_id, showtime_id) REFERENCES seat_hold (id, showtime_id),
    CONSTRAINT show_seat_booking_fk FOREIGN KEY (booking_id, showtime_id) REFERENCES booking (id, showtime_id),
    CONSTRAINT show_seat_status_ck CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    CONSTRAINT show_seat_price_ck CHECK (price > 0),
    -- Row shape matches status (docs/architecture.md §4.3).
    CONSTRAINT show_seat_status_shape_ck CHECK (
        (status = 'AVAILABLE' AND hold_id IS NULL AND hold_expires_at IS NULL AND booking_id IS NULL)
        OR (status = 'HELD' AND hold_id IS NOT NULL AND hold_expires_at IS NOT NULL AND booking_id IS NULL)
        OR (status = 'BOOKED' AND hold_id IS NULL AND hold_expires_at IS NULL AND booking_id IS NOT NULL)
    )
);

CREATE INDEX show_seat_hold_idx ON show_seat (hold_id) WHERE hold_id IS NOT NULL;
CREATE INDEX show_seat_booking_idx ON show_seat (booking_id) WHERE booking_id IS NOT NULL;

CREATE TABLE booking_seat (
    booking_id  uuid   NOT NULL,
    showtime_id bigint NOT NULL,
    seat_id     bigint NOT NULL,
    CONSTRAINT booking_seat_pk PRIMARY KEY (booking_id, seat_id),
    -- The "never sold twice" backstop (AC-B8): at most one booking per seat per showtime, ever.
    CONSTRAINT booking_seat_showtime_seat_uk UNIQUE (showtime_id, seat_id),
    CONSTRAINT booking_seat_booking_fk FOREIGN KEY (booking_id, showtime_id) REFERENCES booking (id, showtime_id),
    CONSTRAINT booking_seat_show_seat_fk FOREIGN KEY (showtime_id, seat_id) REFERENCES show_seat (showtime_id, seat_id)
);

-- Transactional outbox: rows are written in the booking transaction. No foreign key to booking,
-- so the outbox stays a generic integration seam (ADR 0002).
CREATE TABLE outbox_event (
    id             uuid        CONSTRAINT outbox_event_pk PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL,
    published_at   timestamptz,
    CONSTRAINT outbox_event_aggregate_type_ck CHECK (btrim(aggregate_type) <> ''),
    CONSTRAINT outbox_event_event_type_ck CHECK (btrim(event_type) <> ''),
    CONSTRAINT outbox_event_payload_ck CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT outbox_event_published_ck CHECK (published_at IS NULL OR published_at >= created_at)
);

CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;
