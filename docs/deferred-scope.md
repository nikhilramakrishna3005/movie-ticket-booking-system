# Deferred Scope

> **Status: CURRENT (Task 14 final audit, 2026-11-03).** This lists every significant feature
> that a production movie-booking platform would have and that Cinéva deliberately does not.
> For each one it gives the reason and what was built instead. The scope baseline is
> `AGENTS.md` (assignment constraints) and `docs/product-scope.md`; the original take-home brief
> is not stored in this repository. Nothing below is implemented unless the "Built instead"
> column says so.

The deciding rule throughout was the priority order in `AGENTS.md` §1: correctness first, then
testability, then explicit tradeoffs, within a 10-hour budget where scope is cut before quality.
Infrastructure deferrals were accepted by the human owner as ADRs; the other cuts were made by
the owner in planning (`docs/implementation-plan.md` §3) or task prompts (`docs/ai-workflow.md`
§6).

## 1. Infrastructure

| Deferred | Why | Built instead |
|---|---|---|
| **Redis** (cache, seat holds, rate limiting) | ADR [0001](decisions/0001-defer-redis.md). A second store for seat state would create a second source of truth and a consistency problem that PostgreSQL row locks already solve; no measurement shows a read bottleneck. | Seat ownership lives only in PostgreSQL: ordered `SELECT … FOR UPDATE` with a 2 s `lock_timeout`, a partial unique index for one active hold, and `UNIQUE (showtime_id, seat_id)` on booked seats. |
| **Kafka** (or any broker) | ADR [0002](decisions/0002-defer-kafka.md). There is one consumer and one process; a broker adds operations cost without a consumer that needs it. | Transactional outbox written in the booking transaction, relayed by an in-process poller (`FOR UPDATE SKIP LOCKED`, `claim_token` fencing, bounded backoff) into an idempotent `notification_inbox`. At-least-once, local only (`docs/architecture.md` §9). |
| **Apache Solr** (search) | ADR [0003](decisions/0003-defer-solr.md). A few thousand showtimes per city and day do not need a separate search cluster and its sync pipeline. | PostgreSQL `pg_trgm` word similarity for typo-tolerant movie and theater search, with filters and capped pagination (`docs/architecture.md` §11). |
| **Production deployment**: HTTPS, multiple instances, autoscaling, CDN, read replicas, connection pooler | Local-only assessment (`AGENTS.md` §2); no cloud dependencies allowed. | Docker Compose on `127.0.0.1` with nginx as a same-origin proxy. One backend instance; multi-instance clock skew is documented (`docs/architecture.md` §7). |
| **Scale features** for 500,000 visitors in 10 minutes: virtual waiting room, rate limiting, bot protection | Requires infrastructure and load testing outside the budget. | Design notes only (`docs/architecture.md` §12): short transactions, narrow locks, payment outside transactions, lazy expiry, stateless app tier. |
| **Load and soak testing** | No production-like environment; numbers from a laptop would not support a capacity claim. | `EXPLAIN` of hot queries and one bounded read probe (`ab -n 500 -c 10`), recorded as sample-size evidence only (`docs/test-strategy.md`). |
| **Observability**: metrics, tracing, structured logs, alerting | Not needed to prove correctness locally. | `GET /actuator/health` only (details hidden). Outbox failures are inspectable in `outbox_event.last_error`. |
| **CI pipeline** (e.g. GitHub Actions) | Not required by the brief; Testcontainers and Playwright need Docker on the runner. | `./run-tests.sh` runs every suite locally and fails on any failure, skipped test or unhealthy stack. Results of each task are recorded in `docs/test-strategy.md`. |

## 2. Payments and money

| Deferred | Why | Built instead |
|---|---|---|
| **Real payment provider**, card data, PCI scope | Explicit non-goal (`docs/product-scope.md` §4); no external services. | `SimulatedPaymentProvider` in-process with its own ledger and `SUCCESS` / `DECLINE` / `TIMEOUT` scenarios. Idempotency keys, `UNKNOWN` outcomes, voiding a late capture, and a guard that refuses to run inside a database transaction (`docs/architecture.md` §6). |
| **Scheduled payment reconciliation** | Background worker on the cut list (`docs/implementation-plan.md` §3, item 2). | On-demand resolution when the client polls `GET /api/payments/{id}`, with a ledger fence so a stale `PENDING` can never be resolved while its charge is still in flight. Limitation: an `UNKNOWN` attempt that nobody polls stays unresolved (failure case F10). |
| **Customer cancellation and refunds** | Non-goal; real refunds need a real provider. | Confirmed bookings are final. Admins can cancel only a showtime with no bookings, no unresolved payments and no live holds, so no refund is ever implied (`POST /api/admin/showtimes/{id}/cancel`). |
| **Pricing features**: dynamic pricing, coupons, loyalty, taxes, fees, gift cards, multiple currencies | Non-goal. | Two fixed prices per showtime (standard and premium), set by the admin; one currency. |
| **Food and beverage, add-ons** | Non-goal. | — |

## 3. Identity and accounts

| Deferred | Why | Built instead |
|---|---|---|
| **Sign-up, password reset, OAuth / SSO, MFA** | Non-goal; real identity management is outside the assessment. | Spring Security HTTP Basic with Flyway-seeded, BCrypt-hashed demo accounts that are labeled public and local-only. |
| **Sessions, tokens, "remember me"** | `AGENTS.md` forbids storing credentials or tokens in browser storage. | Credentials kept in React memory only. A reload signs the user out; checkout can recover an active hold through `GET /api/holds/{id}` after signing in again. |
| **Platform-admin role and catalog management** (movies, cities, theaters) | Non-goal; no platform-admin role in scope. | Catalog, cities and theaters are seeded by Flyway. Theater admins manage screens and showtimes for their assigned theaters only. |

## 4. Product features

| Deferred | Why | Built instead |
|---|---|---|
| **Hold-cleanup sweeper** | Background worker on the cut list; correctness must not depend on job timing. | Lazy expiry: every read and write compares `expires_at` with the injected UTC `Clock`, so an expired hold stops blocking exactly at expiry. Showtime cancellation also sweeps expired holds in its own transaction. |
| **Real-time seat updates** (WebSocket / server-sent events) | Extra infrastructure for a demo-sized load; the hold request is the authority anyway. | The seat map refreshes every 15 s; a seat taken in the meantime is refused at hold time (409 `SEATS_UNAVAILABLE`) and dropped from the selection. |
| **Editing or rescheduling a showtime** | Not in the admin scope; would need rules for seats already held or sold. | Admins create and cancel showtimes. A screen layout can be replaced only before the screen's first showtime. |
| **Email, SMS or push notifications** | Non-goal; no external services. | The local `notification_inbox`, readable by its owner through `GET /api/notifications` and the `/notifications` page. |
| **Groups larger than 10 seats** | Bounds lock sets and request size. | 1–10 seats per hold, all-or-nothing. |
| **Recommendations, reviews, trailers** | Non-goal. | Discovery by city, date, typo-tolerant text, genre and language. |
| **Search and filter in My Tickets; cancelled or past booking states** | Not needed for the booking workflow. | My Tickets lists confirmed bookings newest first, 10 at a time, with "Show older tickets". |
| **Mobile apps, internationalization, accessibility audit** | Non-goal. | A responsive web app in English; no formal accessibility audit and no keyboard-only E2E test. |
| **Outbox dead-letter handling and admin retry UI** | Not needed for one local consumer. | After 8 failed claims an event stays unpublished with `last_error` for inspection by SQL. |
| **Sample data beyond 2027-01-31** | Seed data is deterministic and fixed. | `docs/sample-data.md` explains how to extend the window with a new migration. |

## 5. Verification not performed

These are gaps in evidence, not missing features. Each is also listed in
`docs/test-strategy.md` ("What the tests do not prove").

| Not performed | Covered instead by |
|---|---|
| E2E tests for payment timeout / `UNKNOWN` polling, hold expiry and the notification inbox | Backend integration tests (`PaymentApiIT`, `HoldApiIT`, `OutboxDeliveryIT`) and frontend unit tests; hold expiry and the inbox were also checked by hand in Task 14 |
| Browsers other than Chromium; mobile viewports; keyboard-only use | Not covered |
| React component tests | Logic is extracted into pure modules with unit tests (`paymentFlow`, `selection`, `history`, `forms`); screens are covered by Playwright |
| Automated architecture-boundary tests (ArchUnit, Spring Modulith) | Code review, as `AGENTS.md` §3 allows |
| Mapping of a PostgreSQL deadlock (`40P01`) to 409; outbox retry exhaustion after 8 attempts | Code review; the lock order is designed to prevent deadlocks and is tested by `HoldConcurrencyIT` |
| Process-kill crash recovery | Crash states are simulated (stale `PENDING` attempts, expired outbox leases, inbox rows without `published_at`) |
| A human re-run of every recorded command | The AI agent's runs are recorded in `docs/test-strategy.md`; the owner reviewed results and used the running app by hand |
