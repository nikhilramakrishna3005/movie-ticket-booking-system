-- Owned by the theater package: cities, theaters, screens, physical seats, showtimes.

CREATE TABLE city (
    id         bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT city_pk PRIMARY KEY,
    name       text NOT NULL,
    state_code text NOT NULL,
    CONSTRAINT city_name_state_uk UNIQUE (name, state_code),
    CONSTRAINT city_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT city_state_code_ck CHECK (state_code ~ '^[A-Z]{2}$')
);

CREATE TABLE theater (
    id      bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT theater_pk PRIMARY KEY,
    city_id bigint NOT NULL CONSTRAINT theater_city_fk REFERENCES city (id),
    name    text   NOT NULL,
    address text   NOT NULL,
    -- Leading city_id column also serves "theaters in a city" lookups.
    CONSTRAINT theater_city_name_uk UNIQUE (city_id, name),
    CONSTRAINT theater_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT theater_address_ck CHECK (btrim(address) <> '')
);

CREATE TABLE screen (
    id         bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT screen_pk PRIMARY KEY,
    theater_id bigint NOT NULL CONSTRAINT screen_theater_fk REFERENCES theater (id),
    name       text   NOT NULL,
    CONSTRAINT screen_theater_name_uk UNIQUE (theater_id, name),
    CONSTRAINT screen_name_ck CHECK (btrim(name) <> '')
);

CREATE TABLE seat (
    id          bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT seat_pk PRIMARY KEY,
    screen_id   bigint  NOT NULL CONSTRAINT seat_screen_fk REFERENCES screen (id),
    row_label   text    NOT NULL,
    seat_number integer NOT NULL,
    category    text    NOT NULL,
    CONSTRAINT seat_screen_row_number_uk UNIQUE (screen_id, row_label, seat_number),
    -- Target of show_seat's composite foreign key: a seat can only be sold for its own screen.
    CONSTRAINT seat_id_screen_uk UNIQUE (id, screen_id),
    CONSTRAINT seat_row_label_ck CHECK (row_label ~ '^[A-Z]{1,2}$'),
    CONSTRAINT seat_number_ck CHECK (seat_number BETWEEN 1 AND 100),
    CONSTRAINT seat_category_ck CHECK (category IN ('STANDARD', 'PREMIUM'))
);

CREATE TABLE showtime (
    id            bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT showtime_pk PRIMARY KEY,
    screen_id     bigint      NOT NULL CONSTRAINT showtime_screen_fk REFERENCES screen (id),
    movie_id      bigint      NOT NULL CONSTRAINT showtime_movie_fk REFERENCES movie (id),
    language_code text        NOT NULL CONSTRAINT showtime_language_fk REFERENCES language (code),
    starts_at     timestamptz NOT NULL,
    ends_at       timestamptz NOT NULL,
    status        text        NOT NULL,
    created_at    timestamptz NOT NULL,
    -- Target of show_seat's composite foreign key (see seat_id_screen_uk).
    CONSTRAINT showtime_id_screen_uk UNIQUE (id, screen_id),
    CONSTRAINT showtime_time_range_ck CHECK (ends_at > starts_at),
    CONSTRAINT showtime_status_ck CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    -- Backstop for AC-U4: scheduled showtimes on one screen never overlap. Half-open ranges
    -- allow back-to-back showtimes. Cancelled showtimes do not block the slot.
    CONSTRAINT showtime_no_overlap_ex EXCLUDE USING gist (
        screen_id WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (status = 'SCHEDULED')
);

CREATE INDEX showtime_movie_idx ON showtime (movie_id);
