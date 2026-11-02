# ADR 0002: Defer Kafka (booking events and downstream integration)

- **Status:** Accepted (2026-11-02)
- **Date:** 2026-11-02 (revised 2026-11-02 after review of PR #1)
- **Status history:** Proposed 2026-11-02 (PR #1, merged with the planning documents); Accepted 2026-11-02 by the human engineering owner during review of PR #9, which kept the component deferred for the reasons below.
- **Deciders:** human engineering owner (accepted 2026-11-02)
- **Implementation note (final audit, 2026-11-02):** as decided, Kafka is not used. It is absent from `backend/pom.xml`, `frontend/package.json` and `docker-compose.yml`. The booking transaction writes a `BookingConfirmed` row to `outbox_event` (asserted by `PaymentApiIT`). No relay or broker delivers it; `published_at` stays `NULL`.

## Context / Problem

Confirmed bookings must trigger downstream work: customer notifications (email/SMS, simulated
here) and potential integrations (analytics, theater systems, accounting). Requirements:

- An event must be emitted **if and only if** the booking commits (no lost events, no events for
  rolled-back bookings).
- Notification failures must not roll back or block a confirmed booking.
- Delivery may be retried; consumers must tolerate duplicates.

A common approach is publishing events to Kafka. Publishing directly from application code after
the database commit (a "dual write") risks losing events on a crash between commit and publish.

## Alternatives considered

| Option | Summary |
|---|---|
| A. Kafka with direct publish after commit | Simple code; dual-write risk. |
| B. Kafka fed by a transactional outbox (poller or CDC such as Debezium) | Reliable; needs Kafka + relay/CDC infrastructure. |
| C. Transactional outbox in PostgreSQL + in-process scheduled relay | Outbox row written in the booking transaction; a scheduler delivers to a stub notifier. |
| D. Synchronous notification call inside the booking flow | Simplest; couples booking success to notifier availability. |

## Decision

**Option C for the assessment build. Kafka is deferred.**

- **Required:** the `booking` package writes an `outbox_event` row in the same transaction that
  creates the booking, so the event exists exactly when the booking exists. Tests assert this.
- **Stretch (on the cut list):** a scheduled relay (`FOR UPDATE SKIP LOCKED`) that delivers
  events to a stub notifier and marks them published. Delivery would be at-least-once, so
  consumers deduplicate by event id. It is a background worker, and per the review of PR #1,
  background workers are cut before correctness or required verification. If it is cut, the
  outbox rows remain the documented integration seam and are not delivered.
- The outbox table is the seam where Kafka could later be attached (Option B) without changing
  the booking transaction.

## Tradeoffs

**Why not Kafka now:**
- There is exactly one consumer (a stub notifier) in the same process; a distributed log adds
  infrastructure without adding a needed capability.
- Kafka would still need an outbox (or CDC) to avoid dual writes, so it adds to, rather than
  replaces, Option C's work.
- Increases Docker Compose footprint, startup time, and test complexity within a 10-hour budget.

**Why not D:** booking confirmation would fail or slow down when the notifier is unavailable.

**Costs of deferring:**
- The outbox poller adds periodic queries against PostgreSQL.
- No independent consumer scaling, replay, or fan-out to many external systems.

## Operational cost (if adopted)

- Kafka brokers (or a managed service), topic/partition design, retention, schema management
  (e.g. schema registry), consumer-group monitoring and lag alerting.
- A relay or CDC connector (Debezium/Kafka Connect) to move outbox rows into Kafka.
- Additional containers for local development and Testcontainers-based tests.

## When Kafka could become justified

- Multiple independent consumers (notifications, analytics, partner/theater integrations) need
  the same booking events, possibly owned by different teams or services.
- Event volume or consumer lag makes a PostgreSQL-polled outbox a measurable bottleneck.
- Replay of historical events is required (e.g. rebuilding read models, analytics backfills).
- A module (e.g. notifications) is extracted from the monolith into a separate service.
