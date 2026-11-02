# AI-Assisted Workflow

> **Status: PLANNED WORKFLOW.** This document describes how AI assistance is intended to be
> used. The findings sections are intentionally empty placeholders; they must be filled with
> real observations as work happens. Nothing below should be read as a record of reviews or
> results that have taken place, unless it is explicitly dated and attributed.

## 1. Principles

- **Human-led.** A human engineer owns every decision: scope, architecture, what is merged,
  and what is claimed to work.
- **AI is implementation assistance, not the final engineering authority.** AI agents draft
  documents, code, and tests; they do not approve their own work.
- **Evidence over assertion.** AI output is trusted only to the extent it is verified by tests
  that a human has seen run, or by human review.
- **Small steps.** AI tasks are small and reviewable (see `AGENTS.md` §6). Each produces a
  report of changed files, tests actually executed, assumptions, and Git operations.

## 2. Workflow per task

1. **Define the task (human).** One phase or sub-phase from `docs/implementation-plan.md`,
   with acceptance criteria and the expected verification checkpoint.
2. **Implement (AI-assisted).** The agent works on a feature branch, following `AGENTS.md`.
3. **Report (AI).** Files changed with reasons, exact test commands and actual results,
   assumptions, open questions, Git operations.
4. **Review (human).** Read the diff, not just the summary. Specifically check:
   - Transaction boundaries (no payment call inside a transaction).
   - Lock ordering and all-or-nothing hold behavior.
   - Database constraints actually present in migrations.
   - Tests assert meaningful outcomes (DB state, not just HTTP status) and could actually fail.
   - No secrets, no scope creep, no undocumented dependencies.
5. **Verify (human).** Re-run the test commands locally; for UI phases, exercise the flow manually.
6. **Decide (human).** Request changes, or merge the PR into `main` (humans only).
7. **Record.** Log notable review findings and AI mistakes in §4 and §5 below.

## 3. What AI is and is not used for

| Used for | Not used for |
|---|---|
| Drafting planning documents and ADRs | Final architecture decisions |
| Generating boilerplate, DTOs, migrations, tests | Deciding that tests are sufficient |
| Proposing concurrency test designs | Declaring code correct without test evidence |
| Explaining unfamiliar APIs | Merging, approving, or pushing to `main` |
| Suggesting edge cases and failure modes | Inventing results, benchmarks, or reviews |

## 4. Review findings

Record only findings from human reviews that actually took place. Do not pre-populate.

### 4.1 PR #1: planning documents (Phase 0)

- **What was reviewed:** the AI-drafted planning documents only. **No application code exists.**
  Nothing below is verification of code, tests, or runtime behavior.
- **Outcome:** changes requested by the human engineering owner (2026-11-02). The owner
  approved the following design decisions, which revision 2 of the documents applies as
  **proposed review corrections**. They remain unimplemented and unverified until the
  corresponding phases pass their checkpoints.

| Date | Phase / PR | Finding / decision from review | Action taken (documentation only) |
|---|---|---|---|
| 2026-11-02 | Phase 0 / PR #1 | Expired holds: the draft proposed allowing conversion of an expired hold if its seats were still free. Owner decided an expired hold is **never** converted; a late capture is voided. | Updated architecture §6.3/§6.8, AC-B5, F6, and the Phase 3 checkpoint. |
| 2026-11-02 | Phase 0 / PR #1 | Payment retries: precise attempt states, no double charge per idempotency key, new key after a decline, unknown outcomes not treated as declines, and honest crash-recovery limits were required. | Added architecture §6.2–§6.7, AC-B3/B4/B6/B7, F5/F7–F10. |
| 2026-11-02 | Phase 0 / PR #1 | Time: the draft left the DB `NOW()` vs. application `Clock` choice open. Owner decided on an injectable UTC `Clock` everywhere, passed to SQL, and multi-instance skew documented as a limitation. | Added architecture §7; `AGENTS.md` coding standards. |
| 2026-11-02 | Phase 0 / PR #1 | Auth: the draft left the mechanism open. Owner decided on HTTP Basic + Spring Security with seeded demo users, no persistent browser storage of credentials, theater-scoped admin and hold-ownership checks, and HTTPS required for real deployment. | Added architecture §8 and §8.1; product scope §2; AC-U1–U4; `AGENTS.md` secrets section. |
| 2026-11-02 | Phase 0 / PR #1 | Concurrency: deterministic lock order, a bounded lock wait, an explicit unique booked-seat constraint, held vs. booked semantics, missing/duplicate seat ids, overlapping requests, and an implementable one-active-hold rule were required. | Added architecture §4.2, §4.3, §5; AC-H4/H5/H7/H8. |
| 2026-11-02 | Phase 0 / PR #1 | Modular monolith: the draft described "public API (service interfaces)" and a `PaymentGateway` interface. Owner asked to avoid speculative interfaces and excess layers. | Concrete service classes; `SimulatedPaymentProvider` as one class; notification package folded into `booking`; `AGENTS.md` §3. |
| 2026-11-02 | Phase 0 / PR #1 | Time management: prioritize Compose clean start, real-PostgreSQL tests, concurrency tests, and 2 Playwright tests; cut admin UI and background workers first; account for required docs. | Rewrote `docs/implementation-plan.md`. |
| 2026-11-02 | Phase 0 / PR #1 | Documentation accuracy: keep all features marked proposed; record review corrections without claiming verification. | This section; status banners in all documents. |

## 5. AI mistakes observed

_Placeholder — to be filled in with actual mistakes made by AI assistance (e.g. incorrect
code, false claims, scope creep) and how they were caught. Do not pre-populate. Whether any
item in §4.1 counts as an AI mistake (rather than a design choice the owner changed) is for
the human owner to classify; none have been entered here._

| Date | Phase / PR | Mistake | How it was caught | Correction |
|---|---|---|---|---|
| 2026-11-02 | Phase 1 / PR #2 (schema) | The AI-written `payment_attempt_failure_reason_ck` accepted a `DECLINED` attempt with a `NULL` reason: `NULL IN (…)` makes the CHECK evaluate to `NULL`, which PostgreSQL treats as passing. | Before commit, the AI agent ran the `DatabaseSchemaIT` assertions against a throwaway local PostgreSQL 16 (Docker was unavailable). The test `aDeclineMustRecordAReason` failed. | Added an explicit `failure_reason IS NOT NULL` to the CHECK in the not-yet-published V5 migration; the test then passed. Not yet reviewed by a human. |
| 2026-11-02 | Phase 2 (seat holds) | The AI agent first wrote the seat and hold locks as JPA `@Lock(PESSIMISTIC_WRITE)`, assuming Hibernate emits `FOR UPDATE`. On PostgreSQL it emits `FOR NO KEY UPDATE`. | The AI agent ran the app against local PostgreSQL with SQL logging and read the generated statements. | Rewrote the locking reads as native `SELECT … FOR UPDATE` so the lock clause matches the design and is visible in code. Not yet reviewed by a human. |
| 2026-11-02 | Phase 2 (seat holds) | A code comment claimed that without an explicit flush, the replacement hold's INSERT would hit the one-active-hold index before the old hold's UPDATE. Removing the flush did not fail any test: the next native query already auto-flushes. | Mutation check by the AI agent: removed the flush and re-ran `HoldApiIT` on local PostgreSQL; all tests still passed. | Kept the explicit flush (so correctness does not rely on implicit auto-flush) and corrected the comment. No test currently distinguishes the two; noted in the PR. |

## 6. Reflections

_Placeholder — to be written at the end of the assessment, based on the entries above._
