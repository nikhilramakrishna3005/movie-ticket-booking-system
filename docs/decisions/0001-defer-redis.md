# ADR 0001: Defer Redis (caching, seat holds, rate limiting)

- **Status:** Proposed
- **Date:** 2026-11-02 (revised 2026-11-02 after review of PR #1)
- **Deciders:** _Pending human review_

## Context / Problem

Redis is commonly suggested for ticketing systems for three purposes:

1. **Seat holds** — storing temporary holds with a TTL (`SET key value NX PX ttl`), so expiry is automatic.
2. **Read caching** — caching catalog data, showtime lists, and seat maps to absorb read spikes
   (e.g. 500,000 visitors in 10 minutes during a major release).
3. **Rate limiting / admission control** — counters and token buckets shared across app instances.

The core requirement is that **no seat can ever be sold twice**, and the architecture
designates **PostgreSQL as the authoritative source of seat ownership**.

## Alternatives considered

| Option | Summary |
|---|---|
| A. Redis for seat holds | Holds live in Redis with TTL; PostgreSQL stores only confirmed bookings. |
| B. Redis as a read cache only | PostgreSQL owns all seat state; Redis caches read-heavy data. |
| C. No Redis; PostgreSQL holds, no cache | Holds are rows in `show_seat` with `hold_expires_at`; lazy expiry; reads go to PostgreSQL. |

## Decision

**Option C for the assessment build. Redis is deferred.** No cache of any kind (including
in-process caches) is added unless a measured problem justifies it.

- Seat holds are stored in PostgreSQL in the same rows that are locked for booking, so hold
  and booking state cannot diverge.
- Lazy expiry gives TTL semantics without Redis and without a background worker. Inside the
  locking transaction, a hold whose `expires_at <= now` (with `now` taken from the injected UTC
  `Clock` and passed to SQL) is treated as expired.
- An expired hold is never converted into a booking (`docs/architecture.md` §6.3). Keeping
  holds and bookings in the same database makes this check a single locked read.

## Tradeoffs

**Why not A (holds in Redis):**
- Splits seat ownership across two stores. Confirming a booking would need coordination between
  Redis and PostgreSQL; failure between the two creates inconsistent states (hold lost while
  payment succeeds, or booking written while Redis still shows a hold).
- Redis persistence/failover semantics (async replication) can lose recently written keys,
  which would silently release holds.
- Adds a second correctness-critical system to test with Testcontainers.

**Costs of deferring:**
- Hold writes and seat-map reads all hit PostgreSQL. At real release scale, this concentrates
  load on the primary database.
- No shared cache or shared rate limiter across multiple app instances.

## Operational cost (if adopted)

- One more container in Docker Compose and in Testcontainers setups.
- Production: HA setup (Sentinel/Cluster or managed service), memory sizing, eviction policy,
  persistence configuration, monitoring, and cache-invalidation logic in application code.
- New failure modes: stale cache, cache stampede on hot showtimes, Redis unavailability handling.

## When Redis could become justified

- Load testing shows PostgreSQL read load (catalog, showtime lists, seat maps) is the bottleneck
  and read replicas / HTTP caching are insufficient → **Option B (read cache)**.
- Multiple app instances need a **shared rate limiter** or **virtual waiting room** for hot releases.
- Even then, Redis should remain a cache/admission layer; **PostgreSQL stays authoritative for
  seat ownership**. Moving holds to Redis would require a new ADR with an explicit consistency design.
