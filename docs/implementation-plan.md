# Implementation Plan

> **Status: HISTORICAL PLAN.** This is the plan as approved on PR #1, kept unchanged below as a
> record. The phases were later implemented and merged through PRs #2–#4 and #6–#13; the current
> state and its verification are in `docs/requirements-checklist.md` and `docs/test-strategy.md`.
> A phase counted as complete only after its verification checkpoint had actually been run and
> the commands and results were recorded in the task report.
>
> Revision 2 reorders the plan per the engineering owner's review of PR #1. The new order puts
> Docker Compose clean-start verification, real-PostgreSQL integration tests, concurrency
> tests, two Playwright tests, and required documentation first, and cuts optional features first.

## 1. Budget and priorities

**Total budget: 10.0 engineering hours**, including documentation and a 0.5 h buffer.

Priority order when trading off:

1. Correctness of holds, payment states, and booking: no double sale, and no booking from an
   expired hold.
2. Integration tests against **real PostgreSQL** (Testcontainers), including the concurrency tests.
3. **Docker Compose clean-start verification** (`down -v`, then `up --build` from scratch).
4. **Two Playwright E2E tests** against the Compose stack.
5. **Required documentation:** README, roles & permissions, data model/ERD, API reference,
   test strategy.
6. Minimal React customer flow (only what the E2E tests and a demo need).
7. Everything else (search ranking polish, admin extras, background workers, styling).

## 2. Phases

Each phase is one or a few small PRs on feature branches, reviewed and merged by a human.

| # | Phase | Budget |
|---|---|---|
| 0 | Planning documents (incl. this review revision) | 1.00 h |
| 1 | Skeleton, Flyway schema, security, Testcontainers, Docker Compose clean start | 1.25 h |
| 2 | Seat holds, locking, and concurrency tests | 2.00 h |
| 3 | Payment attempts, simulated provider, confirmation, void, idempotency | 1.50 h |
| 4 | Read APIs, search, admin create-showtime API | 0.75 h |
| 5 | Minimal React customer flow | 1.25 h |
| 6 | Playwright E2E tests + clean-start verification | 0.75 h |
| 7 | Required documentation | 1.00 h |
| — | Buffer | 0.50 h |
| | **Total** | **10.00 h** |

### Phase 0: Planning documents (1.00 h)
- **Scope:** `AGENTS.md`, `docs/product-scope.md`, `docs/architecture.md`, this plan,
  `docs/ai-workflow.md`, ADRs 0001–0003, and the review revision.
- **Checkpoint:** The engineering owner approves PR #1. Open items in `docs/architecture.md`
  §14 are confirmed or explicitly deferred.

### Phase 1: Skeleton, schema, security, test harness, Compose (1.25 h)
- **Scope:**
  - Spring Boot project (Java 17+) with packages `catalog`, `theater`, `booking`, `payment`,
    `admin`, `security`.
  - `Clock` bean (`Clock.systemUTC()`), and UTC JDBC time zone.
  - Flyway: all core tables and **all constraints from architecture §4.3**, `pg_trgm`, and seed
    data (cities, theaters, screens, seats, movies, showtimes, demo users with BCrypt hashes,
    admin assignments).
  - Spring Security HTTP Basic (stateless).
  - Testcontainers base test with a mutable test `Clock`.
  - Docker Compose (`postgres`, `backend`; frontend added in Phase 5) and `.env.example` with
    placeholders only.
- **Checkpoint:**
  - Build passes (`./mvnw verify` or Gradle equivalent), including integration tests that:
    Flyway migrates on Testcontainers PostgreSQL; a duplicate `booking_seat` insert violates the
    unique constraint; a second `ACTIVE` hold row for the same customer and showtime violates
    the partial unique index; an unauthenticated protected request returns 401.
  - **Clean start:** `docker compose down -v && docker compose up --build`, then the health
    endpoint returns `UP`.

### Phase 2: Seat holds, locking, concurrency (2.00 h) (highest priority)
- **Scope:** Create hold (architecture §5.3: validation, lock order, `lock_timeout`, lazy expiry,
  one-active-hold replacement), release hold (§5.6), seat-map effective status, ownership
  checks (404).
- **Checkpoint:** Integration tests for AC-H1 through AC-H10 pass, including:
  - **Concurrency:** e.g. 50 threads released together by a latch request the same seat; exactly
    one 201, the rest 409, and exactly one `HELD` row owned by the winner.
  - **Overlap:** concurrent requests for overlapping seat sets in different orders; no deadlock
    errors; each request holds all its seats or none.
  - **Bounded wait:** a test holds a row lock in a separate transaction; the hold request fails
    with `SEATS_BUSY` within the timeout.
  - **Expiry:** the test clock is advanced past the TTL (no sleeps); another customer can then
    hold the seats; the same customer's new hold replaces the expired one.

### Phase 3: Payment, confirmation, void, idempotency (1.50 h)
- **Scope:** `payment_attempt` state machine (architecture §6.2–§6.3), `SimulatedPaymentProvider`
  with its ledger and transaction guard, TX2/TX3/void flow, on-demand `resolve`, outbox write in TX3.
- **Checkpoint:** Integration tests for AC-B1 through AC-B9 pass, including:
  - `SUCCESS` gives a booking; `DECLINE` gives `DECLINED`, and a retry with a new key succeeds.
  - Same key repeated (sequentially and concurrently): provider charge count is 1.
  - **Late capture:** the test clock is advanced past expiry between `charge` and TX3; the
    attempt is `VOIDED`, there is no booking, and the seats are not `BOOKED`. This is run both
    with seats still free and with seats re-held by someone else.
  - `TIMEOUT` gives `UNKNOWN` (never `DECLINED`); `resolve` gives `SUCCEEDED` (hold valid) or
    `VOIDED` (clock advanced past expiry).
  - Crash simulation: a `PENDING` attempt with a `CAPTURED` ledger entry resolves correctly; a
    `PENDING` attempt with no ledger entry resolves to `DECLINED (NOT_RECEIVED)`.
  - The provider throws if invoked inside a transaction.
  - **Concurrency:** concurrent confirmations for the same hold give exactly one booking.

### Phase 4: Read APIs, search, admin API (0.75 h)
- **Scope:** Cities, showtime search (`pg_trgm` plus filters, paginated), showtime detail;
  `POST /api/admin/showtimes` with theater scoping and overlap check. (Delivered as Task 06; the
  endpoint path changed from the proposed `/api/admin/theaters/{id}/showtimes` per the task
  brief. Movies listing and per-city time zones were added in the same task.)
- **Checkpoint:** Integration tests for AC-S1 through AC-S5 and AC-U1 through AC-U4 pass.

### Phase 5: Minimal React customer flow (1.25 h)
- **Scope:** Login (credentials in memory only), search, seat map, checkout with countdown and
  scenario selector, unknown-status polling message, confirmation page. `data-testid`
  attributes for E2E. Add `frontend` to Docker Compose. (Delivered as Task 07. Refinement of
  the original "new key per Pay click": a key belongs to one payment *attempt*; retrying after an
  ambiguous result resends the same key, and only a definitive decline starts a new attempt
  with a new key.)
- **Checkpoint:** After a clean start, a human completes one booking in the browser and records
  the result. A check of browser storage (DevTools) shows no credentials in
  `localStorage`/`sessionStorage`/cookies.

### Phase 6: Playwright E2E + clean-start verification (0.75 h)
- **Scope:** Two Playwright tests against the Compose stack (AC-E1 happy path, AC-E2 decline).
  A script or documented command sequence for a clean start followed by the E2E run.
  (Delivered as Task 08, with an extra seat-conflict test. Each test creates its own showtime
  through the admin API instead of relying on seed inventory, so runs need no reset between them.)
- **Checkpoint:** From `docker compose down -v`, the stack starts and `npx playwright test`
  passes. Run it **twice** from a clean start to check for flakiness, and record the results.

### Phase 7: Required documentation (1.00 h)
- **Scope (each item required):**
  - `README.md`: prerequisites, clean-start commands, demo credentials (labeled local-only and
    non-secret), how to run each test suite, known limitations.
  - **Roles & permissions:** update the matrix in `docs/architecture.md` §8.1 to match the
    implementation.
  - **Data model / ERD:** update `docs/architecture.md` §4 to match the actual migrations.
  - **API reference:** `docs/api.md`, written from the implemented controllers.
  - **Test strategy:** `docs/test-strategy.md`, covering the test levels, what each suite
    proves, how determinism is achieved (test clock, latches), and the commands actually used.
  - Update the implementation-status table (`docs/architecture.md` §13) and `docs/ai-workflow.md`
    with real findings only.
- **Checkpoint:** A human follows only the README from a clean clone, starts the stack, and runs
  the test suites; the results are recorded.
- **Delivered in the final audit (2026-11-02).** The ERD and API reference became separate files
  (`docs/data-model.md`, `docs/api.md`), and a requirements checklist was added
  (`docs/requirements-checklist.md`). The AI agent followed the README in a fresh clone in the
  agent VM, with results in the checklist §10. **A human run of the checkpoint is not recorded.**

## 3. Cut list

Cut in this order (first item is cut first). Cutting is decided by the human owner, and each
cut is recorded in the README's known limitations.

1. Admin UI (already excluded from the plan).
2. Background workers: hold-cleanup sweeper, scheduled payment reconciliation. ~~Outbox relay.~~
   Implemented in Task 13 as an in-process PostgreSQL poller (not Kafka).
3. ~~Admin showtime cancellation.~~ Implemented in Task 12 (`POST /api/admin/showtimes/{id}/cancel`).
4. "My bookings" page (the confirmation page is sufficient).
5. Live seat-map refresh/polling (refresh on navigation only).
6. Search ranking polish (keep filters plus basic trigram similarity).
7. UI styling beyond basic usability.

**Never cut:** the constraints in architecture §4.3; lock order and bounded lock wait; the
expired-hold-never-converts rule and its test; the idempotency test (charge count = 1);
`UNKNOWN` handling; the concurrency tests; real-PostgreSQL integration tests; Docker Compose
clean start; the two Playwright tests; README, roles & permissions, ERD, API reference, and
test strategy.

## 4. Proposed test mapping

Written before any tests existed and kept as planned. The implemented tests and their mapping to
the criteria are in `docs/test-strategy.md` and `docs/requirements-checklist.md` §7.

| Area | Level | Criteria | Phase |
|---|---|---|---|
| Schema constraints | Integration (Testcontainers) | AC-B8, AC-H8 backstop | 1 |
| Auth basics | Integration | AC-U1 | 1 |
| Hold rules and validation | Integration | AC-H1, AC-H2, AC-H7, AC-H9, AC-H10 | 2 |
| Hold concurrency, overlap, bounded wait | Integration (multi-threaded) | AC-H3, AC-H4, AC-H5 | 2 |
| Expiry and one-active-hold | Integration (test clock) | AC-H6, AC-H8 | 2 |
| Payment state machine | Unit + integration | AC-B1, AC-B2, AC-B3, AC-B6, AC-B7 | 3 |
| Idempotency | Integration (incl. concurrent) | AC-B4 | 3 |
| Expired hold never converts | Integration (test clock) | AC-B5 | 3 |
| Transaction guard | Integration | AC-B9 | 3 |
| Search | Integration | AC-S1 – AC-S5 | 4 |
| Admin scoping | Integration | AC-U2 – AC-U4 | 4 |
| End-to-end | Playwright on Compose | AC-E1, AC-E2 | 6 |

## 5. Risks

| Risk | Mitigation |
|---|---|
| Concurrency tests are flaky | Start threads with a latch; assert on database state; no timing assertions except the lock-timeout bound. |
| Time-dependent tests are flaky | Mutable test `Clock`; no `NOW()` in business SQL; no sleeps for expiry. |
| Docker/Testcontainers setup consumes time | Phase 1 proves the harness and a clean Compose start before any feature work. |
| Playwright flakiness | `data-testid` selectors, Playwright auto-waiting, fresh seed data from a clean start. |
| Payment flow complexity exceeds budget | Phase 3 has the second-largest budget; UI handles only `SUCCEEDED`, `DECLINED`, `UNKNOWN` (poll), `VOIDED` messages. |
| Time overrun | Track actual hours per phase; apply the cut list early. |

## 6. Time tracking

Fill in actual time only as work happens. Do not pre-fill or estimate after the fact.

**Final audit note (2026-11-02):** time was not tracked precisely during the work, so the
"Actual" column is left empty rather than reconstructed. Git commit timestamps exist, but they
record when commits were made, not engineering effort.

| Phase | Budget | Actual | Notes |
|---|---|---|---|
| 0 | 1.00 h | _TBD_ | |
| 1 | 1.25 h | _TBD_ | |
| 2 | 2.00 h | _TBD_ | |
| 3 | 1.50 h | _TBD_ | |
| 4 | 0.75 h | _TBD_ | |
| 5 | 1.25 h | _TBD_ | |
| 6 | 0.75 h | _TBD_ | |
| 7 | 1.00 h | _TBD_ | |
| Buffer | 0.50 h | _TBD_ | |
