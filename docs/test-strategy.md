# Test Strategy

> **Status: IMPLEMENTED.** This document describes the tests that exist in the repository and what
> each suite proves. The counts are from the Task 10 run on 2026-11-03 (see
> [Task 10 verification](#task-10-verification-2026-11-03)). The final audit of 2026-11-02 is kept
> below it.

## Test levels

| Level | Tool | Location | Count | Command |
|---|---|---|---|---|
| Backend unit | JUnit 5 (Surefire) | `backend/src/test/java/**/*Test.java` | 64 | `cd backend && ./mvnw -B clean verify` |
| Backend integration and concurrency | JUnit 5, Spring Boot, Testcontainers PostgreSQL 16 (Failsafe) | `backend/src/test/java/**/*IT.java` | 142 | (same command) |
| Frontend unit | Node built-in test runner | `frontend/src/**/*.test.ts` | 40 | `cd frontend && npm test` |
| End-to-end | Playwright, Chromium | `frontend/e2e/*.spec.ts` | 6 | `cd frontend && npm run test:e2e` (needs the Compose stack running) |

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

## Backend unit tests (64)

| Class | Proves |
|---|---|
| `ApplicationContextWithoutDatabaseTest` (28) | The security filter chain without a database: 401 with or without a Basic challenge, wrong password or unknown user, role checks (customer vs admin), anonymous browsing paths, non-API paths denied, no CORS headers granted, error format for unknown paths, UTC clock bean. |
| `HoldRulesTest` (4) | Hold and seat expiry at the exact expiry instant; only active holds can be released or expired. |
| `PaymentAttemptRulesTest` (4) | The attempt state machine: `PENDING` reaches every outcome; `UNKNOWN` stays in flight; terminal states never change. |
| `ShowtimeSearchTest` (5) | Search defaults, page-size cap, invalid paging rejected with the parameter name, text trimming. |
| `ShowtimeSchedulingRulesTest` (10) | Local start time converted with the right offset (including DST); field validation for new showtimes. |
| `AccessPolicyTest` (7) | Ownership (another customer's resource looks nonexistent) and theater-scoped admin rules. |
| `ClockConfigTest` (1) | The application clock is UTC. |
| `BookingPageRequestTest` (5) | Booking-history paging: defaults to page 0 of 10; an oversized size is capped at 50, not rejected; page 0–1000 and size 1 are accepted; a negative or too-deep page and a size below 1 are rejected with the parameter name. |

## Backend integration tests on Testcontainers (142)

| Class | Proves | Acceptance criteria |
|---|---|---|
| `DatabaseSchemaIT` (33) | All migrations apply in order. The booking-history index is `(customer_id, created_at DESC, id DESC)` and the redundant old index is gone. Every constraint in `docs/data-model.md` rejects the invalid row it guards, e.g. a seat booked twice, two active holds, two in-flight attempts, a reasonless decline, overlapping showtimes, and a booking for another customer's hold. Sample data shape and window. | AC-B8, AC-H8 backstop, AC-U4 backstop |
| `HoldApiIT` (22) | Hold creation, validation (empty, over 10, duplicate, unknown or other-screen seats), all-or-nothing conflicts, lazy expiry exactly at `expires_at`, one active hold per showtime with expired-hold replacement, release (idempotent, owner-only, 404 for others), closed showtimes. | AC-H1, H2, H6–H10 |
| `HoldConcurrencyIT` (5) | Many customers racing for one seat leave exactly one holder. Overlapping multi-seat requests never deadlock or partially succeed. The same customer racing two holds gets one. The lock wait is bounded and reported as `SEATS_BUSY` with nothing held. | AC-H3, H4, H5 |
| `PaymentApiIT` (17) | Success books exactly once and writes one outbox event. Decline books nothing and a retry needs a new key. Timeout is `UNKNOWN` until a poll resolves it. Late capture after hold expiry is voided, even with the seats still free. Key replay charges once. Key reuse for another hold is rejected. Others' holds, payments and bookings give 404. No release or new attempt while in flight. The provider is only called outside a transaction. A failed confirmation rolls back fully. Minimum remaining time, converted holds and the attempt limit are enforced. | AC-B1–B7, B9, AC-H10 |
| `PaymentConcurrencyIT` (4) | Concurrent requests with the same key, or with different keys, give one charge and one booking. Concurrent polls of an `UNKNOWN` payment confirm it once and never void a confirmed booking. A release racing a payment never leaves a released hold booked. | AC-B4, B7 |
| `SimulatedProviderFenceIT` (4) | The ledger fence used for stale `PENDING` resolution: a charge queued behind a fence is refused, and a fence behind a capture reports the capture. | AC-B6, architecture §6.7 |
| `DiscoveryApiIT` (25) | City and local-date filtering, combined filters, typo tolerance, pagination without gaps, page cap, local date vs UTC date, DST, default "today" per city, started or cancelled shows not listed, showtime detail, anonymous browsing allowed but holds, payments and bookings protected. | AC-S1–S5, AC-U1 |
| `AdminShowtimeApiIT` (11) | An assigned admin creates a showtime with full seat inventory, which is then searchable and holdable. Other theaters give 403. Overlaps give 409, back-to-back is allowed. Concurrent overlapping creates produce exactly one showtime. The screen lock wait is bounded (`SCREEN_BUSY`). Invalid requests list the fields. | AC-U2, U4 |
| `SecurityIT` (10) | 401 for missing or wrong credentials. Customers are forbidden from admin endpoints. An authenticated unknown path gives 404, not 401. An admin principal carries only its seeded assignment. A customer can't reach another customer's resource. | AC-U1, U3 |
| `BookingHistoryApiIT` (9) | `GET /api/bookings`: 401 without or with wrong credentials, 403 for a theater admin, an empty page for a customer with no bookings. A booking is listed with its reference, movie, theater, screen, city, local date and time in the theater's zone, seats and total. Alice never sees Bob's bookings, even with `?customerId=` in the query, and Bob still gets 404 for Alice's `GET /api/bookings/{id}`. Newest first across pages, with no gaps or repeats and an empty page past the end. Bookings made at the same clock instant are ordered by id, so pages stay stable. Declined and unpaid holds are not listed. `page` and `size` are bounded (400 with the parameter name; size capped at 50). Bookings are made through the real hold and payment endpoints, with the `MutableClock` setting their times. | Product scope §3.6, AC-U1 |
| `ApplicationIT` (2) | The application connects to PostgreSQL and health is `UP` with the database. | — |

## Frontend unit tests (40)

| File | Proves |
|---|---|
| `src/api/client.test.ts` (13) | Credentials sent as Basic auth from memory, with `X-Requested-With`. Typed errors. A payment is retried after a network failure with the **same** key. Hold creation is never retried. A decline is never retried. The booking history is requested with `page` and `size` and no customer parameter, and a rejected request is a typed 401. |
| `src/features/tickets/history.test.ts` (4) | My Tickets pages are concatenated in order; a booking shifted onto the next page by a newer booking is not shown twice; whether another page exists; a ticket is "upcoming" until its showtime starts. |
| `src/features/checkout/paymentFlow.test.ts` (7) | `UNKNOWN` is pending, never declined. A decline retires the key so the next try is a new attempt. Ambiguous failures keep the key. 401 asks for sign-in without dropping the attempt. |
| `src/features/seats/selection.test.ts` (3) | Seats that became held or booked are dropped from the selection; exact price totals. |
| `src/features/discovery/showtimes.test.ts` (4) | Grouping and ordering of search results; reading every page. |
| `src/lib/lib.test.ts` (9) | Dates in the theater zone, DST arithmetic, countdown skew correction, formatting (including the ticket date), UUID generation. |

## End-to-end tests (6)

Playwright drives Chromium against the real Compose stack (nginx, Spring Boot, PostgreSQL). Each
test creates its own showtime through `POST /api/admin/showtimes`, so tests are independent and
can run in parallel without a database reset. Selectors are `data-testid` only.

| Test | Proves | Criteria |
|---|---|---|
| `booking.spec.ts`: a customer books seats and the booked seats become unavailable | An anonymous visitor searches, opens a showtime, selects a standard and a premium seat, signs in, holds and pays. The ticket shows the reference, movie, theater, date, time, seats and total. The booking is `CONFIRMED` through the API. The seats are then shown booked and can't be selected. | AC-E1 |
| `booking.spec.ts`: a declined payment books nothing and a retry with a new attempt books exactly once | A decline shows the declined state with no ticket, and the seats stay `HELD`. The retry sends a different `Idempotency-Key` (402, then 201). Exactly the chosen seats end up booked, in one booking. | AC-E2, AC-B3 |
| `seat-conflict.spec.ts`: a seat taken by another customer is removed from the selection… | When another customer holds a selected seat first, the hold is refused, that seat is deselected, and the rest can be booked. | AC-H2 (through the UI) |
| `search-navigation.spec.ts`: a showtime opened while the search debounce is pending stays open, and Back returns to Discover | Regression for the Task 10 race (below). The seat map stays open after the debounce fires; Back returns to Discover with the city and date; Forward returns to the seats. | Search §3.1 |
| `search-navigation.spec.ts`: a search typed on Discover still filters the results and is kept in the history entry | The misspelled "Intersteller" filters to one movie, and after opening a showtime, Back restores `q` in the URL and in the search box. | AC-S3 (through the UI) |
| `my-tickets.spec.ts`: a successful booking appears in My Tickets, opens its ticket, and the list is gone after reload or sign-out | Alice books two premium seats in the UI, opens My Tickets from the header and finds the card with the reference, movie, local date and time, theater, seats, total and "Upcoming". The cards are in the API's newest-first order. The card opens the existing ticket page. Local and session storage and cookies are empty. After a reload she is signed out and sees no tickets; signed in as Bob, Alice's booking is absent; after sign-out, `/tickets` asks to sign in. | Product scope §3.6 |

### The search race regression

**Bug.** Typing a search and clicking a showtime within the 300 ms debounce sent the user back
to Discover. React Router applies navigations inside a React transition, so Discover stays
mounted until the seat page renders. When the debounce fired in that window, Discover's
`setSearchParams` called `navigate('?…', { replace: true })`, which resolves against the route
still rendered (Discover) and replaced the new `/showtimes/{id}/seats` entry with `/?…&q=…`.
Instrumenting `history` on the running stack showed exactly that: `pushState` to the seats
page, then `replaceState` back to `/?…&q=Inception`, 57 ms later.

**Deterministic reproduction.** Waiting real time would make the test flaky, so the test
controls both clocks involved:
- `page.clock` (Playwright's fake timers) owns the debounce's `setTimeout`; the test fires it
  with `clock.runFor(300)` at a chosen moment.
- React's scheduler runs renders through `MessageChannel`, which `page.clock` does not fake. An
  init script wraps `MessagePort.prototype.postMessage` so the test can hold those messages,
  keeping the transition to the seat page uncommitted while the debounce fires, then release
  them.

Before the fix the race test failed 5 out of 5 runs. After the fix the two search tests passed
20/20 (10 repeats).

**Fix.** `useCityAndDate().update` drops a URL change when `window.location.pathname` no
longer matches the page that owns the filters, i.e. the user has already navigated away. The
debounce, the search filters and Back/Forward are unchanged.

**Limitation.** A search typed but not yet committed to the URL when the user leaves (less than
300 ms) is not saved in the Discover history entry, so Back shows Discover without that `q`.

## What the tests do not prove

- **Performance and scale.** There is no load or soak test, and no measurement of the
  500,000-visitor target or of index use at scale (`EXPLAIN`).
- **Multi-instance behavior.** All tests run one application instance; clock skew between
  instances is untested.
- **Real payment providers.** All payment tests use the in-process simulator, whose ledger has
  properties real providers don't guarantee (architecture §6.7).
- **Browser coverage.** E2E runs Chromium only. Timeout/`UNKNOWN` polling, hold expiry,
  keyboard-only use and mobile viewports have no E2E test. The My Tickets empty, error and
  "Show older tickets" states have no E2E test; their logic is covered by `history.test.ts`. The `UNKNOWN` flow is covered by
  backend tests and frontend unit tests.
- **Package boundaries.** These are enforced by review only (no ArchUnit).
- **Crash recovery.** This is tested by simulating states, e.g. a stale `PENDING` attempt, not by
  killing the process.

## Task 10 verification (2026-11-03)

Run on a Mac (macOS 26, Docker Desktop) on branch `feature/task-10-search-race-and-my-bookings`.
The backend was built with JDK 23 (the build targets Java 21). The existing Compose stack and
its database were kept: the stack was rebuilt in place, not reset.

| Command | Result |
|---|---|
| `cd backend && ./mvnw -B clean verify` | Unit tests: 64 run, 0 failures, 0 errors, 0 skipped. Integration tests: 142 run, 0 failures, 0 errors, 0 skipped. BUILD SUCCESS |
| `cd frontend && npm test` | 40 tests, 40 pass, 0 fail |
| `cd frontend && npm run build` | Type-check and Vite build succeed |
| `docker compose up -d --build` | postgres, backend and frontend healthy; Flyway at V10; the booking made by hand before the task was still present |
| `cd frontend && npx playwright test` | 6 passed |
| `cd frontend && npx playwright test --repeat-each=3` | 18 passed |
| `./run-tests.sh` (all suites, stack running) | Exit 0. Backend 64 unit and 142 integration (0 failures, 0 errors, 0 skipped), frontend 40/40 (0 skipped), Playwright 6 passed |

A clean `docker compose down -v` start was not run for Task 10, to keep the owner's database.

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
