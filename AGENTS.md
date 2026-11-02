# AGENTS.md

Instructions for any AI coding agent (and any human) contributing to this repository.
Read this file in full before making changes.

## 1. Project goals

This repository is a take-home engineering assessment for the Copart SDET Internship:
a **Movie Ticket Booking System** (similar to BookMyShow / Fandango).

It is an **engineering assessment, not a production deployment**. In priority order:

1. **Correctness** — above all, no seat may ever be sold twice.
2. **Testability** — behavior is proven by automated tests, especially concurrency tests.
3. **Explicit tradeoffs** — every simplification is written down, not hidden.
4. **Clear ownership** — module boundaries are respected; each change has an obvious owner and reviewer.

The total development budget is **10 engineering hours** (see `docs/implementation-plan.md`).
Scope is cut before quality is cut.

Planning documents:

| Document | Purpose |
|---|---|
| `docs/product-scope.md` | Roles, MVP workflows, non-goals, acceptance criteria |
| `docs/architecture.md` | Modular monolith design, booking flow, consistency boundaries |
| `docs/implementation-plan.md` | Phased 10-hour plan with verification checkpoints |
| `docs/ai-workflow.md` | How AI assistance is used and reviewed |
| `docs/decisions/` | Architecture Decision Records (ADRs) |

## 2. Technology constraints

Use only the following unless an ADR in `docs/decisions/` approves otherwise:

| Area | Technology |
|---|---|
| Backend | Java 17+, Spring Boot, REST (JSON) |
| Database | PostgreSQL (authoritative source of seat ownership) |
| Schema migrations | Flyway (no `ddl-auto` schema generation) |
| Frontend | React |
| Local runtime | Docker Compose (the whole system must run locally with no cloud dependencies) |
| Backend tests | JUnit 5, Spring Boot integration tests, Testcontainers (real PostgreSQL) |
| UI end-to-end tests | Playwright (at least 2 tests) |
| Payments | Simulated in-process provider (success, failure, timeout) — no real payment provider |

Redis, Kafka, and Apache Solr are **deferred** (see ADRs 0001–0003). Do not add them,
or any other infrastructure, without a new or updated ADR accepted by a human reviewer.

## 3. Module boundaries

The backend is a **modular monolith**: one deployable Spring Boot application, split into
packages with explicit boundaries.

| Module | Owns | May depend on |
|---|---|---|
| `catalog` | Movies, languages, genres, search | — |
| `theater` | Cities, theaters, screens, seat layouts, showtimes | `catalog` (movie ids, via its public API) |
| `booking` | Seat inventory per showtime, holds, bookings, outbox events | `theater` (showtime/seat lookup), `payment` (gateway interface) |
| `payment` | Simulated payment provider, payment attempts | — |
| `admin` | Theater-admin REST endpoints / use cases | `catalog`, `theater` public APIs |
| `notification` | Consumes booking outbox events (stubbed delivery) | `booking` events only |

Rules:

- A module exposes a small public API (service interfaces + DTOs). Other modules must not
  import its internal classes, repositories, or JPA entities.
- A module only writes to its own tables. Cross-module reads go through the owning module's
  public API, not direct SQL joins into another module's tables (read-only reporting queries
  are the only exception and must be documented).
- Only the `booking` module may change seat ownership state.
- The `payment` module is never called inside a database transaction (see `docs/architecture.md`).
- If module boundaries are enforced by a test (e.g. ArchUnit or Spring Modulith), keep it passing.

## 4. Coding standards

- Follow standard Java conventions; prefer constructor injection, immutable DTOs (records),
  and small, focused classes.
- Keep controllers thin: validation and mapping only; business rules live in services.
- Transactions are declared at the service layer and kept short. No network calls, sleeps,
  or payment calls inside a transaction.
- Every schema change is a new Flyway migration. Never edit a migration that has been committed.
- Use database constraints (unique, foreign key, check) as a backstop for invariants, not only
  application code.
- Time-dependent logic (hold expiry) must take an injectable `Clock` so it can be tested.
- No dead code, commented-out code, or speculative abstractions "for later".
- Comments explain non-obvious constraints and tradeoffs, not what the next line does.
- Frontend: functional React components; keep API access in one client module; add stable
  `data-testid` attributes for elements used by Playwright tests.

## 5. Test expectations

- **Unit tests (JUnit 5)** for pure business rules (hold validity, pricing, state transitions).
- **Integration tests (Spring Boot + Testcontainers PostgreSQL)** for anything that touches
  the database, transactions, or locking. Do not use H2 or another substitute for PostgreSQL.
- **Concurrency tests** are mandatory for seat holds and booking confirmation: many threads
  competing for the same seats must result in exactly one winner per seat.
- **Payment scenarios**: success, failure (decline), and timeout must each have an automated test.
- **Playwright**: at least 2 end-to-end tests against the Docker Compose stack
  (minimum: successful booking; and a failure path such as payment decline or seat conflict).
- Tests must be deterministic: no reliance on wall-clock sleeps where a `Clock` or latch can be used.
- A failing test is never deleted or disabled to make a build pass without a human's explicit
  approval and a written reason.

## 6. Working rules for agents

### Task size and reporting

- Work in **small, reviewable tasks**. One task = one logical change that a human can review
  in a single sitting. If a task grows, stop and propose a split.
- Do not work beyond the task you were given. Do not start the next phase without human approval.
- At the end of every task, report:
  1. **Every file created, modified, or deleted**, with a one-line explanation of why.
  2. **Tests actually executed** — the exact commands and their actual results (pass/fail counts).
     If no tests were run, say "No tests were run" explicitly.
  3. Assumptions made and open questions.
  4. Any Git commits, branches, or pushes performed.

### Honesty

- **Never claim that unverified code works.** "Implemented" is not "verified".
  Use precise language: "written, not run", "compiles", "tests pass (N/N)", "manually verified in browser".
- Never invent test results, review findings, benchmarks, or implementation outcomes.
- Never describe proposed features as implemented. Documentation must label status explicitly.

### Git

- **Never push to `main`. Never merge into `main`.** All work happens on feature branches and
  is merged only by a human after review.
- **Never squash, rebase onto rewritten history, amend pushed commits, or force-push.**
  Commit history must remain an honest record of the work.
- Do not create unnecessary commits (e.g. "fix typo" chains for your own unpushed work —
  get it right before committing). One commit per logical change.
- Write descriptive commit messages explaining what changed and why.

### Secrets and configuration

- **Keep secrets out of the repository.** No real credentials, tokens, or keys in code,
  config, tests, fixtures, or docs.
- Local-only development credentials (e.g. the Docker Compose PostgreSQL password) are supplied
  through environment variables with a committed `.env.example` containing placeholder values only.
- `.env` and other local secret files must be git-ignored.
