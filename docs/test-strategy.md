# Test Strategy

> **Status: IMPLEMENTED.** This document describes the tests that exist in the repository and what
> each suite proves. The counts are from the final verification run on 2026-11-02 (see
> [Final verification](#final-verification-2026-11-02)).

## Test levels

| Level | Tool | Location | Count | Command |
|---|---|---|---|---|
| Backend unit | JUnit 5 (Surefire) | `backend/src/test/java/**/*Test.java` | 59 | `cd backend && ./mvnw -B clean verify` |
| Backend integration and concurrency | JUnit 5, Spring Boot, Testcontainers PostgreSQL 16 (Failsafe) | `backend/src/test/java/**/*IT.java` | 132 | (same command) |
| Frontend unit | Node built-in test runner | `frontend/src/**/*.test.ts` | 34 | `cd frontend && npm test` |
| End-to-end | Playwright, Chromium | `frontend/e2e/*.spec.ts` | 3 | `cd frontend && npm run test:e2e` (needs the Compose stack running) |

These counts are executed test cases, including parameterized and repeated cases, as reported
by Surefire, Failsafe, Node and Playwright.

`./run-tests.sh` at the repository root runs all four levels in order against the running Compose
stack. It stops at the first failure and also fails if any suite reports a skipped test. Maven runs
with `-DskipTests=false -Dmaven.test.skip=false`, and Playwright runs with `--forbid-only`, so
neither environment settings nor a stray `.only` can narrow the run.

## Principles

- **Real PostgreSQL only.** Integration tests run against the `postgres:16-alpine` image through
  Testcontainers (`TestcontainersConfiguration`), the same version as Docker Compose. H2 or
  another substitute is not used. Locking, partial unique indexes, exclusion constraints,
  `lock_timeout` and `pg_trgm` behave as in production.
- **Database state, not only HTTP status.** Tests assert rows in `show_seat`, `seat_hold`,
  `payment_attempt`, `booking`, `booking_seat`, `outbox_event`, and the simulated provider's
  ledger.
- **Deterministic time.** A `MutableClock` replaces the `Clock` bean (`TestClockConfiguration`).
  Tests expire holds by advancing the clock, never by sleeping.
- **Deterministic concurrency.** Threads are released together with a `CountDownLatch`, and
  blocking points are ordered with latches or held row locks. Assertions check outcomes, such as
  exactly one winner, and not timing. The only timing bound tested is `lock_timeout`.
- **Nothing hidden.** No test is disabled or skipped (0 skipped in every suite). Playwright
  retries are 0.

## Backend unit tests (59)

| Class | Proves |
|---|---|
| `ApplicationContextWithoutDatabaseTest` (28) | The security filter chain without a database: 401 with or without a Basic challenge, wrong password or unknown user, role checks (customer vs admin), anonymous browsing paths, non-API paths denied, no CORS headers granted, error format for unknown paths, UTC clock bean. |
| `HoldRulesTest` (4) | Hold and seat expiry at the exact expiry instant; only active holds can be released or expired. |
| `PaymentAttemptRulesTest` (4) | The attempt state machine: `PENDING` reaches every outcome; `UNKNOWN` stays in flight; terminal states never change. |
| `ShowtimeSearchTest` (5) | Search defaults, page-size cap, invalid paging rejected with the parameter name, text trimming. |
| `ShowtimeSchedulingRulesTest` (10) | Local start time converted with the right offset (including DST); field validation for new showtimes. |
| `AccessPolicyTest` (7) | Ownership (another customer's resource looks nonexistent) and theater-scoped admin rules. |
| `ClockConfigTest` (1) | The application clock is UTC. |

## Backend integration tests on Testcontainers (132)

| Class | Proves | Acceptance criteria |
|---|---|---|
| `DatabaseSchemaIT` (32) | All migrations apply in order. Every constraint in `docs/data-model.md` rejects the invalid row it guards, e.g. a seat booked twice, two active holds, two in-flight attempts, a reasonless decline, overlapping showtimes, and a booking for another customer's hold. Sample data shape and window. | AC-B8, AC-H8 backstop, AC-U4 backstop |
| `HoldApiIT` (22) | Hold creation, validation (empty, over 10, duplicate, unknown or other-screen seats), all-or-nothing conflicts, lazy expiry exactly at `expires_at`, one active hold per showtime with expired-hold replacement, release (idempotent, owner-only, 404 for others), closed showtimes. | AC-H1, H2, H6–H10 |
| `HoldConcurrencyIT` (5) | Many customers racing for one seat leave exactly one holder. Overlapping multi-seat requests never deadlock or partially succeed. The same customer racing two holds gets one. The lock wait is bounded and reported as `SEATS_BUSY` with nothing held. | AC-H3, H4, H5 |
| `PaymentApiIT` (17) | Success books exactly once and writes one outbox event. Decline books nothing and a retry needs a new key. Timeout is `UNKNOWN` until a poll resolves it. Late capture after hold expiry is voided, even with the seats still free. Key replay charges once. Key reuse for another hold is rejected. Others' holds, payments and bookings give 404. No release or new attempt while in flight. The provider is only called outside a transaction. A failed confirmation rolls back fully. Minimum remaining time, converted holds and the attempt limit are enforced. | AC-B1–B7, B9, AC-H10 |
| `PaymentConcurrencyIT` (4) | Concurrent requests with the same key, or with different keys, give one charge and one booking. Concurrent polls of an `UNKNOWN` payment confirm it once and never void a confirmed booking. A release racing a payment never leaves a released hold booked. | AC-B4, B7 |
| `SimulatedProviderFenceIT` (4) | The ledger fence used for stale `PENDING` resolution: a charge queued behind a fence is refused, and a fence behind a capture reports the capture. | AC-B6, architecture §6.7 |
| `DiscoveryApiIT` (25) | City and local-date filtering, combined filters, typo tolerance, pagination without gaps, page cap, local date vs UTC date, DST, default "today" per city, started or cancelled shows not listed, showtime detail, anonymous browsing allowed but holds, payments and bookings protected. | AC-S1–S5, AC-U1 |
| `AdminShowtimeApiIT` (11) | An assigned admin creates a showtime with full seat inventory, which is then searchable and holdable. Other theaters give 403. Overlaps give 409, back-to-back is allowed. Concurrent overlapping creates produce exactly one showtime. The screen lock wait is bounded (`SCREEN_BUSY`). Invalid requests list the fields. | AC-U2, U4 |
| `SecurityIT` (10) | 401 for missing or wrong credentials. Customers are forbidden from admin endpoints. An authenticated unknown path gives 404, not 401. An admin principal carries only its seeded assignment. A customer can't reach another customer's resource. | AC-U1, U3 |
| `ApplicationIT` (2) | The application connects to PostgreSQL and health is `UP` with the database. | — |

## Frontend unit tests (34)

| File | Proves |
|---|---|
| `src/api/client.test.ts` (11) | Credentials sent as Basic auth from memory, with `X-Requested-With`. Typed errors. A payment is retried after a network failure with the **same** key. Hold creation is never retried. A decline is never retried. |
| `src/features/checkout/paymentFlow.test.ts` (7) | `UNKNOWN` is pending, never declined. A decline retires the key so the next try is a new attempt. Ambiguous failures keep the key. 401 asks for sign-in without dropping the attempt. |
| `src/features/seats/selection.test.ts` (3) | Seats that became held or booked are dropped from the selection; exact price totals. |
| `src/features/discovery/showtimes.test.ts` (4) | Grouping and ordering of search results; reading every page. |
| `src/lib/lib.test.ts` (9) | Dates in the theater zone, DST arithmetic, countdown skew correction, formatting, UUID generation. |

## End-to-end tests (3)

Playwright drives Chromium against the real Compose stack (nginx, Spring Boot, PostgreSQL). Each
test creates its own showtime through `POST /api/admin/showtimes`, so tests are independent and
can run in parallel without a database reset. Selectors are `data-testid` only.

| Test | Proves | Criteria |
|---|---|---|
| `booking.spec.ts`: a customer books seats and the booked seats become unavailable | An anonymous visitor searches, opens a showtime, selects a standard and a premium seat, signs in, holds and pays. The ticket shows the reference, movie, theater, date, time, seats and total. The booking is `CONFIRMED` through the API. The seats are then shown booked and can't be selected. | AC-E1 |
| `booking.spec.ts`: a declined payment books nothing and a retry with a new attempt books exactly once | A decline shows the declined state with no ticket, and the seats stay `HELD`. The retry sends a different `Idempotency-Key` (402, then 201). Exactly the chosen seats end up booked, in one booking. | AC-E2, AC-B3 |
| `seat-conflict.spec.ts`: a seat taken by another customer is removed from the selection… | When another customer holds a selected seat first, the hold is refused, that seat is deselected, and the rest can be booked. | AC-H2 (through the UI) |

## What the tests do not prove

- **Performance and scale.** There is no load or soak test, and no measurement of the
  500,000-visitor target or of index use at scale (`EXPLAIN`).
- **Multi-instance behavior.** All tests run one application instance; clock skew between
  instances is untested.
- **Real payment providers.** All payment tests use the in-process simulator, whose ledger has
  properties real providers don't guarantee (architecture §6.7).
- **Browser coverage.** E2E runs Chromium only. Timeout/`UNKNOWN` polling, hold expiry,
  keyboard-only use and mobile viewports have no E2E test. The `UNKNOWN` flow is covered by
  backend tests and frontend unit tests.
- **Package boundaries.** These are enforced by review only (no ArchUnit).
- **Crash recovery.** This is tested by simulating states, e.g. a stale `PENDING` attempt, not by
  killing the process.

## Final verification (2026-11-02)

Run in the Cursor cloud agent VM (Ubuntu 24.04, Docker 29.1.3, Compose 2.40.3, OpenJDK 21.0.12,
Node 22.23.4) on branch `cursor/final-submission-audit-d450`. The source
was unchanged from `origin/main` at `3081f0d`; only documentation and the `run-tests.sh` runner
were added.

| Command | Result |
|---|---|
| `docker compose down -v` then `docker compose up --build -d --wait` | Volume removed; postgres, backend and frontend all healthy |
| `curl http://localhost:3000/actuator/health` | `{"status":"UP"}` through the frontend proxy |
| `cd backend && ./mvnw -B clean verify` | Unit tests: 59 run, 0 failures, 0 errors, 0 skipped. Integration tests: 132 run, 0 failures, 0 errors, 0 skipped. BUILD SUCCESS |
| `cd frontend && npm test` | 34 tests, 34 pass, 0 fail |
| `cd frontend && npm run build` | Type-check and Vite build succeed |
| `cd frontend && npm run test:e2e` (twice) | 3 passed, then 3 passed |
| `./run-tests.sh` (all suites, stack running) | Exit 0. Backend 59 unit and 132 integration (0 failures, 0 errors, 0 skipped), frontend 34/34 (0 skipped), Playwright 3 passed |

**Runner failure checks.** Each case was run once, and each temporary file was deleted afterwards:

| Case | Exit code and message |
|---|---|
| A failing frontend unit test | 1; `npm test` failed (`# fail 1`) |
| A skipped frontend unit test | 1; `frontend unit tests reported skipped tests` |
| A `test.skip` Playwright test | 1; `Playwright reported skipped tests` |
| Frontend container stopped | 1; `the Compose stack is not healthy at http://localhost:3000` |

The README's clean-checkout steps were also followed in a fresh `git clone`; the results are in
`docs/requirements-checklist.md`.
