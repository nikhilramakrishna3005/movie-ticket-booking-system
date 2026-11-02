# Implementation Plan

> **Status: PLAN ONLY.** No phase below has been started except Phase 0 (this planning work).
> Completion status must be updated only after a phase's verification checkpoint has actually
> been run and its results recorded.

## 1. Budget and priorities

**Total budget: 10.0 engineering hours**, including documentation and a time buffer.

Priority order when trading off:

1. Backend correctness of seat holds and booking (no double sale).
2. Concurrency tests against real PostgreSQL (Testcontainers).
3. Simulated payment success / failure / timeout, with tests.
4. Docker Compose running the full stack locally.
5. Two Playwright end-to-end tests.
6. Required documentation (README run instructions, ADRs, AI workflow findings).
7. Everything else (admin UI polish, search sophistication, styling).

## 2. Phases

Each phase ends in a **verification checkpoint**: a concrete, runnable check. A phase is not
"done" until its checkpoint passes and the results (commands + output summary) are recorded
in the task report. Each phase should be one or a few small, reviewable PRs on feature branches.

| # | Phase | Budget |
|---|---|---|
| 0 | Planning documents | 0.75 h |
| 1 | Skeleton, Docker Compose, schema, test harness | 1.25 h |
| 2 | Catalog, theaters, showtimes, search (read APIs) | 1.00 h |
| 3 | Seat holds + concurrency tests | 2.00 h |
| 4 | Simulated payment, booking confirmation, expiry, outbox | 1.50 h |
| 5 | Minimal theater-admin API | 0.50 h |
| 6 | React UI (customer booking flow) | 1.25 h |
| 7 | Playwright E2E tests | 0.75 h |
| 8 | Documentation finalization | 0.50 h |
| — | Buffer | 0.50 h |
| | **Total** | **10.00 h** |

### Phase 0 — Planning documents (0.75 h)
- **Scope:** `AGENTS.md`, `docs/product-scope.md`, `docs/architecture.md`, this plan,
  `docs/ai-workflow.md`, ADRs 0001–0003.
- **Checkpoint:** Human review of the documents; open questions in
  `docs/architecture.md` §11 and `docs/product-scope.md` §7 answered or explicitly deferred.

### Phase 1 — Skeleton, Docker Compose, schema, test harness (1.25 h)
- **Scope:** Spring Boot project (Java 17+), package-per-module structure, Flyway baseline
  migration for all core tables and constraints (incl. `pg_trgm`, `booking_seat` unique constraint),
  seed data migration (a few cities/theaters/screens/movies/showtimes), Testcontainers base test
  class, Docker Compose with `postgres` + `backend` (+ placeholder for `frontend`), `.env.example`.
- **Checkpoint:**
  - `./mvnw verify` (or Gradle equivalent) passes, including one integration test proving
    Flyway migrations apply on a Testcontainers PostgreSQL.
  - `docker compose up` starts PostgreSQL and the backend; a health endpoint returns `UP`.

### Phase 2 — Catalog, theaters, showtimes, search (1.00 h)
- **Scope:** Read APIs: list cities, search showtimes (city, date, language, genre, text with
  `pg_trgm` typo tolerance), showtime detail, seat map.
- **Checkpoint:** Integration tests for AC-S1…AC-S5 pass (including one typo-tolerance case);
  seat map endpoint returns seeded seats with statuses.

### Phase 3 — Seat holds + concurrency tests (2.00 h) — *highest priority*
- **Scope:** Create hold (ordered `FOR UPDATE`, all-or-nothing, lazy expiry), release hold,
  validation rules (max 10 seats, showtime not started/cancelled, seats belong to screen),
  ownership checks.
- **Checkpoint:**
  - Integration tests for AC-H1…AC-H6 pass.
  - **Concurrency test:** e.g. 50 threads concurrently request the same seat(s) → exactly one
    success, all others 409, and the database shows a single holder. Overlapping multi-seat
    requests in different orders complete without deadlock.
  - Expiry test using an injectable `Clock` (no sleeps).

### Phase 4 — Simulated payment, confirmation, expiry, outbox (1.50 h)
- **Scope:** `PaymentGateway` + simulated provider (success/decline/timeout), payment attempts
  with idempotency key, confirmation transaction (booking + `booking_seat` + outbox), void on
  conflict, timeout status resolution, hold-expiry sweeper, outbox relay to a logging notifier.
- **Checkpoint:**
  - Integration tests for AC-B1…AC-B6 pass, including: success, decline, timeout (captured and
    not captured), duplicate confirm with same idempotency key, late success after hold taken.
  - **Concurrency test:** concurrent confirmations for the same hold produce one booking.
  - Test asserting the payment gateway is not invoked inside an active transaction
    (e.g. checking `TransactionSynchronizationManager.isActualTransactionActive()` in a test double).

### Phase 5 — Minimal theater-admin API (0.50 h)
- **Scope:** Seeded admin users; create showtime (generates `show_seat` rows; rejects overlaps),
  cancel showtime without bookings; theater-scoped authorization.
- **Checkpoint:** Integration tests for AC-A1…AC-A3 pass.

### Phase 6 — React UI (1.25 h)
- **Scope:** Search page, showtime/seat-map page, checkout with countdown and payment scenario
  selector, confirmation page, "my bookings". `data-testid` attributes for E2E tests.
  Add `frontend` service to Docker Compose. Admin UI only if time remains (see cut list).
- **Checkpoint:** `docker compose up` serves the UI; a human manually completes one booking
  in the browser and records the result.

### Phase 7 — Playwright E2E tests (0.75 h)
- **Scope:** At least two tests against the Docker Compose stack:
  1. Happy path: search → select seats → pay (success) → booking reference shown (AC-E1).
  2. Failure path: payment decline → error shown, no booking (AC-E2).
- **Checkpoint:** `npx playwright test` passes against a freshly started stack; run twice to
  check for flakiness. Results recorded.

### Phase 8 — Documentation finalization (0.50 h)
- **Scope:** README (prerequisites, `docker compose up`, how to run each test suite, demo users),
  update implementation-status table in `docs/architecture.md`, fill in `docs/ai-workflow.md`
  with real review findings, record known gaps.
- **Checkpoint:** A clean clone following only the README can start the stack and run the
  tests (verified by a human or a recorded run).

## 3. Cut list (if time runs short)

Cut in this order — first item is cut first. Items **never** cut: Phase 3 concurrency tests,
the `booking_seat` unique constraint, payment success/decline/timeout tests, two Playwright
tests, Docker Compose, README run instructions.

1. Admin UI (admin remains API-only, tested by integration tests).
2. Screen/seat-layout editing (layouts stay seeded).
3. "My bookings" page (confirmation page only).
4. Live seat-map refresh/polling in the UI (refresh on action only).
5. Outbox relay scheduler (keep outbox rows written transactionally; relay documented as next step).
6. Scheduled timeout reconciliation (keep single inline status check; document the gap).
7. Showtime cancellation by admin.
8. Advanced search ranking (keep filters + basic trigram similarity).
9. Hold-expiry sweeper (lazy expiry alone preserves correctness; seat maps may show stale
   "held" until next touch — must then be displayed using the expiry timestamp).

## 4. Risks

| Risk | Mitigation |
|---|---|
| Concurrency tests are flaky | Use latches/barriers to start threads together; assert on DB state, not timing. |
| Docker/Testcontainers environment issues consume time | Phase 1 validates the harness first, before features. |
| Playwright tests flaky against async UI | Use `data-testid` and Playwright auto-waiting; reset seed data per run. |
| Scope creep in UI | UI is minimal and functional; styling is last. |
| Time overrun | Track actual hours per phase; apply the cut list early, not at the end. |

## 5. Time tracking

Actual time spent per phase is to be filled in as work happens. Do not pre-fill.

| Phase | Budget | Actual | Notes |
|---|---|---|---|
| 0 | 0.75 h | _TBD_ | |
| 1 | 1.25 h | _TBD_ | |
| 2 | 1.00 h | _TBD_ | |
| 3 | 2.00 h | _TBD_ | |
| 4 | 1.50 h | _TBD_ | |
| 5 | 0.50 h | _TBD_ | |
| 6 | 1.25 h | _TBD_ | |
| 7 | 0.75 h | _TBD_ | |
| 8 | 0.50 h | _TBD_ | |
| Buffer | 0.50 h | _TBD_ | |
