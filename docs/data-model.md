# Data Model

> **Status: IMPLEMENTED.** This document describes the schema created by the committed Flyway
> migrations `V1`–`V13` in `backend/src/main/resources/db/migration/`. It was written from those
> files; if they disagree, the migrations are authoritative. Hibernate runs with
> `ddl-auto: validate`, so the JPA entities must match this schema at startup.

| Migration | Creates |
|---|---|
| `V1__enable_extensions.sql` | `pg_trgm` (typo-tolerant search), `btree_gist` (showtime overlap exclusion) |
| `V2__catalog_schema.sql` | `language`, `genre`, `movie`, `movie_genre` |
| `V3__theater_schema.sql` | `city`, `theater`, `screen`, `seat`, `showtime` |
| `V4__security_schema.sql` | `app_user`, `theater_admin_assignment` |
| `V5__booking_and_payment_schema.sql` | `seat_hold`, `payment_attempt`, `simulated_provider_charge`, `booking`, `show_seat`, `booking_seat`, `outbox_event` |
| `V6__search_indexes.sql` | Trigram GIN indexes on `movie.title` and `theater.name`; partial index on scheduled `showtime.starts_at` |
| `V7__sample_data.sql` | Sample catalog, cities, theaters, screens, seats, showtimes and `show_seat` inventory (`docs/sample-data.md`) |
| `V8__demo_users.sql` | Four public demo users (BCrypt hashes) and two theater-admin assignments |
| `V9__city_time_zone.sql` | `city.time_zone` (IANA zone id) |
| `V10__booking_history_index.sql` | `booking_customer_history_idx (customer_id, created_at DESC, id DESC)` for `GET /api/bookings`; drops `booking_customer_idx`, which was its prefix |
| `V11__e2e_test_users.sql` | Playwright-only customers `e2e.alice` and `e2e.bob` (BCrypt hashes); Alice and Bob are unchanged |
| `V12__outbox_relay_and_notification_inbox.sql` | Outbox claim/retry columns (`claimed_until`, `next_attempt_at`, `attempt_count`, `last_error`); `notification_inbox` keyed by `event_id` |
| `V13__outbox_claim_token.sql` | `outbox_event.claim_token` so ack and failure apply only while that claim still owns the row |

## Entity-relationship diagram

```mermaid
erDiagram
    LANGUAGE ||--o{ MOVIE : "original language"
    LANGUAGE ||--o{ SHOWTIME : "shown in"
    MOVIE ||--o{ MOVIE_GENRE : ""
    GENRE ||--o{ MOVIE_GENRE : ""
    CITY ||--o{ THEATER : has
    THEATER ||--o{ SCREEN : has
    SCREEN ||--o{ SEAT : has
    SCREEN ||--o{ SHOWTIME : hosts
    MOVIE ||--o{ SHOWTIME : "scheduled as"
    SHOWTIME ||--o{ SHOW_SEAT : "inventory"
    SEAT ||--o{ SHOW_SEAT : "sold per showtime"
    APP_USER ||--o{ THEATER_ADMIN_ASSIGNMENT : "assigned"
    THEATER ||--o{ THEATER_ADMIN_ASSIGNMENT : "managed by"
    APP_USER ||--o{ SEAT_HOLD : places
    SHOWTIME ||--o{ SEAT_HOLD : "for"
    SEAT_HOLD ||--o{ SHOW_SEAT : "holds (while HELD)"
    SEAT_HOLD ||--o{ PAYMENT_ATTEMPT : "paid by"
    SEAT_HOLD ||--o| BOOKING : "converts to"
    PAYMENT_ATTEMPT ||--o| BOOKING : "pays for"
    BOOKING ||--o{ SHOW_SEAT : "owns (when BOOKED)"
    BOOKING ||--|{ BOOKING_SEAT : contains
    SHOW_SEAT ||--o| BOOKING_SEAT : "sold once"

    LANGUAGE {
        text code PK "two lower-case letters"
        text name UK
    }
    GENRE {
        bigint id PK
        text name UK
    }
    MOVIE {
        bigint id PK
        text title
        text original_language_code FK
        integer duration_minutes "1-600"
        text certification "G | PG | PG-13 | R | NC-17 | NR"
        date release_date
    }
    MOVIE_GENRE {
        bigint movie_id PK, FK
        bigint genre_id PK, FK
    }
    CITY {
        bigint id PK
        text name
        text state_code "UK (name, state_code)"
        text time_zone "IANA zone, V9"
    }
    THEATER {
        bigint id PK
        bigint city_id FK
        text name "UK (city_id, name)"
        text address
    }
    SCREEN {
        bigint id PK
        bigint theater_id FK
        text name "UK (theater_id, name)"
    }
    SEAT {
        bigint id PK
        bigint screen_id FK
        text row_label "one or two capital letters"
        integer seat_number "1-100"
        text category "STANDARD | PREMIUM"
    }
    SHOWTIME {
        bigint id PK
        bigint screen_id FK
        bigint movie_id FK
        text language_code FK
        timestamptz starts_at
        timestamptz ends_at
        text status "SCHEDULED | CANCELLED"
        timestamptz created_at
    }
    APP_USER {
        bigint id PK
        text username UK
        text password_hash "BCrypt"
        text role "CUSTOMER | THEATER_ADMIN"
        text display_name
        timestamptz created_at
    }
    THEATER_ADMIN_ASSIGNMENT {
        bigint user_id PK, FK
        text user_role "always THEATER_ADMIN"
        bigint theater_id PK, FK
    }
    SEAT_HOLD {
        uuid id PK
        bigint customer_id FK
        bigint showtime_id FK
        text status "ACTIVE | CONVERTED | RELEASED | EXPIRED"
        timestamptz expires_at
        timestamptz created_at
    }
    SHOW_SEAT {
        bigint showtime_id PK, FK
        bigint seat_id PK, FK
        bigint screen_id FK
        text status "AVAILABLE | HELD | BOOKED"
        uuid hold_id FK "only when HELD"
        timestamptz hold_expires_at "only when HELD"
        uuid booking_id FK "only when BOOKED"
        numeric price
    }
    PAYMENT_ATTEMPT {
        uuid id PK
        uuid hold_id FK
        text idempotency_key UK
        text scenario "SUCCESS | DECLINE | TIMEOUT"
        numeric amount
        text status "PENDING | SUCCEEDED | DECLINED | UNKNOWN | VOIDED"
        text failure_reason "PROVIDER_DECLINED | NOT_RECEIVED, only when DECLINED"
        timestamptz created_at
        timestamptz updated_at
    }
    SIMULATED_PROVIDER_CHARGE {
        text idempotency_key PK "no FK: models an external system"
        numeric amount
        text status "CAPTURED | DECLINED | VOIDED"
        timestamptz created_at
        timestamptz updated_at
    }
    BOOKING {
        uuid id PK
        text reference UK "6-12 of A-Z, 0-9"
        bigint customer_id FK
        bigint showtime_id FK
        uuid hold_id UK, FK
        uuid payment_attempt_id UK, FK
        numeric total_amount
        text status "CONFIRMED"
        timestamptz created_at
    }
    BOOKING_SEAT {
        uuid booking_id PK, FK
        bigint showtime_id FK "UK (showtime_id, seat_id)"
        bigint seat_id PK, FK
    }
    OUTBOX_EVENT {
        uuid id PK
        text aggregate_type
        uuid aggregate_id "no FK: generic seam (ADR 0002)"
        text event_type
        jsonb payload
        timestamptz created_at
        timestamptz published_at "ack after inbox commit"
        timestamptz claimed_until "lease; NULL when idle"
        timestamptz next_attempt_at
        integer attempt_count
        text last_error
        uuid claim_token "NULL when idle or after ack"
    }
    NOTIFICATION_INBOX {
        uuid id PK
        uuid event_id UK "idempotency key"
        bigint customer_id FK
        uuid booking_id
        text event_type
        jsonb payload
        timestamptz created_at
    }
```

## Constraints that protect the invariants

Each of these exists in the migrations and is exercised by `DatabaseSchemaIT` (run on
Testcontainers PostgreSQL 16).

| Invariant | Constraint |
|---|---|
| A seat is never sold twice | `booking_seat_showtime_seat_uk UNIQUE (showtime_id, seat_id)` |
| One hold and one payment produce at most one booking | `booking_hold_uk`, `booking_payment_attempt_uk` |
| A booking belongs to its hold's customer and showtime | `booking_hold_fk (hold_id, customer_id, showtime_id) → seat_hold` |
| A booking's payment belongs to its hold | `booking_payment_attempt_fk (payment_attempt_id, hold_id) → payment_attempt` |
| An idempotency key maps to one attempt | `payment_attempt_idempotency_key_uk` |
| At most one in-flight attempt per hold | partial unique index `payment_attempt_one_in_flight_per_hold_uk … WHERE status IN ('PENDING','UNKNOWN')` |
| The simulated provider charges at most once per key | `simulated_provider_charge_pk (idempotency_key)` |
| A declined attempt always records why | `payment_attempt_failure_reason_ck` (explicit `IS NOT NULL`) |
| One active hold per customer per showtime | partial unique index `seat_hold_one_active_per_customer_showtime_uk … WHERE status = 'ACTIVE'` |
| A seat row's columns match its status | `show_seat_status_shape_ck` |
| A seat is sold only for its own screen's showtimes | composite FKs `show_seat (showtime_id, screen_id) → showtime` and `(seat_id, screen_id) → seat` |
| A held seat references a hold of the same showtime | `show_seat_hold_fk (hold_id, showtime_id) → seat_hold` |
| Scheduled showtimes on a screen never overlap | `showtime_no_overlap_ex EXCLUDE USING gist (screen_id WITH =, tstzrange(starts_at, ends_at) WITH &&) WHERE status = 'SCHEDULED'` |
| Only theater admins can be assigned to theaters | `theater_admin_assignment_user_fk (user_id, user_role) → app_user (id, role)` with `user_role = 'THEATER_ADMIN'` |

Timestamps have no database defaults. The application sets them from the injected UTC `Clock`
(`docs/architecture.md` §7). The one default in V9 (`city.time_zone`) backfills existing rows
and is dropped in the same migration.

## Screen layouts (no new migration)

Task 11 adds screens and can replace a seating plan **only while that screen has never had a
showtime**, including cancelled or past ones. After the first showtime, `show_seat` and
`booking_seat` reference the physical seats, so changing them would rewrite tickets. The
application takes the same `screen` `FOR UPDATE` lock that showtime creation uses; the
composite FK `show_seat (seat_id, screen_id) → seat` (no `ON DELETE`) is the database backstop.
No Flyway migration was added.

## Booking history reads

`GET /api/bookings` adds no columns and copies no data. A page of bookings is read through
`booking_customer_history_idx`, filtered by `status = 'CONFIRMED'`, and sorted by
`created_at DESC, id DESC`, so ties are broken the same way on every request. The seats, the
showtime, theater, city and time zone, and the movie title are then read from their own
tables in batches, one query each per page. `DatabaseSchemaIT.bookingHistoryIsIndexedByCustomerNewestFirst`
checks the index definition and that the old index is gone. The query plan has not been
measured at scale.

## Tables that exist but are not fully used

- `outbox_event`: a `BookingConfirmed` row is written in the booking transaction. The in-process
  dispatcher claims unpublished due rows (`FOR UPDATE SKIP LOCKED` plus a per-claim `claim_token`),
  the inbox consumer inserts with `ON CONFLICT (event_id) DO NOTHING`, then `published_at` is set
  only if that token still matches. Kafka is not used (ADR 0002).
- `showtime.status = 'CANCELLED'`: set by `POST /api/admin/showtimes/{id}/cancel` when the show
  has no bookings, no unresolved payments and no live holds. Search, holds and confirmation
  already honoured the status. Inventory and historical rows are never deleted.
