# AI-Assisted Workflow

> **Status: RECORD OF THE WORKFLOW AS USED.** Sections 1–3 describe the intended workflow, written
> in Phase 0. Sections 4–7 record what actually happened: human review findings, AI mistakes and
> how they were caught, the task prompts given to the AI agent, and the verification performed.
> Entries are dated and say who found each issue. Nothing here is a claim of a review or result
> that did not take place.

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

### 4.2 PR #4: payment and booking confirmation (Phase 3)

| Date | Phase / PR | Finding from review | Action taken |
|---|---|---|---|
| 2026-11-02 | Phase 3 / PR #4 | Reconciliation race: `CheckoutService.resolveIfDue()` treated provider `NOT_FOUND` as a definitive decline once a `PENDING` attempt was older than 5 s, although the original charge could still be executing. A late capture could then leave the attempt `DECLINED` while the ledger said `CAPTURED`. It was only voided if the request thread survived and the void succeeded. | Resolution now fences an unknown key in the provider ledger (`statusOrFence`), so `NOT_FOUND` means "never charged and never will be". Added a latch-ordered API test and provider-level tests that order the charge and the fence on the ledger key. Verified on local PostgreSQL 16 only; Testcontainers not run. Remaining limitation: architecture §6.7. |

### 4.3 Tasks 06–09: discovery APIs, React app, Playwright, final audit

| Date | Task / PR | Human decision or review finding | Action taken |
|---|---|---|---|
| 2026-11-02 | Task 07 / PR #7 (prompt) | The owner required: credentials in memory only; one idempotency key per payment attempt, reused for retries; **no automatic retry with a fresh key after an ambiguous result**; a same-origin reverse proxy instead of permissive CORS; no Playwright yet. | Implemented as specified (`frontend/src/api/client.ts`, `features/checkout/`, `frontend/nginx/`). Covered by `client.test.ts` and `paymentFlow.test.ts`. |
| 2026-11-02 | Task 07 / PR #7 | The interactive browser test session the AI agent started was stopped from the owner's side. | The AI agent switched to a throwaway headless-Chrome script in `/tmp` (not committed) for manual flow checks, and said so in its report. |
| 2026-11-02 | Task 08 / PR #8 | **Human review found** that the E2E fixture `findScreenId()` sent `city=1` to `GET /api/showtimes`, whose parameter is `cityId`. The API ignores unknown parameters, so the filter was silently not applied. | Fixed in `5a3c6d1`: the fixture uses `cityId` and asserts every returned showtime is in Dallas, so an unapplied filter fails the test. Against the running stack, `city=1` returned cities 1, 2 and 3; `cityId=1` returned only city 1. Playwright 3/3 on two runs. |
| 2026-11-02 | Task 09 (final audit, prompt) | The owner asked for a submission audit: honest requirements checklist, documentation from the actual code, Git and secret checks, a clean-start verification, no invented hours, and no new features. | This PR. Results are in `docs/requirements-checklist.md` and `docs/test-strategy.md`. |
| 2026-11-02 | Task 09 / PR #9 (review) | The owner accepted ADRs 0001–0003 (Redis, Kafka and Solr stay deferred), and asked for one root-level command that runs every test suite and fails on any failure or skipped test. | ADR statuses set to Accepted, with the status history kept; `run-tests.sh` added and verified (`docs/test-strategy.md`). |
| 2026-11-03 | Task 10 (prompt) | The owner asked for the search debounce race to be fixed at its cause, with a deterministic Playwright regression that fails first, and for a customer booking history (`GET /api/bookings` plus a My Tickets screen) with the customer taken only from the principal. On the owner's Mac, the existing Compose stack and its database, which held a booking made by hand, had to be kept: "Do not run docker compose down -v or delete any database volume without explicit permission." | Implemented on `feature/task-10-search-race-and-my-bookings`. The stack was only rebuilt in place, and the hand-made booking was checked before and after V10 was applied. |
| 2026-11-03 | Task 11 (prompt) | Complete theater administration with real APIs: assigned theaters only, screens and seating plans, showtime creation, service-layer authorization, and a Cinéva admin dashboard. Screen layouts must not change seats that existing showtimes already reference. Preserve the running Compose database. | Implemented on `feature/task-11-theater-admin`. No new migration: a layout can be replaced only before the first showtime, under the same `screen` lock as scheduling. |
| 2026-11-03 | Task 12 (prompt) | Authorized showtime cancellation that never deletes bookings or silently refunds; `GET /holds/{id}` ownership-scoped; Playwright isolation from Alice and Bob; preserve the running Compose database. | Implemented on `feature/task-12-cancel-and-hold-lookup`. Policy: 409 if bookings, in-flight payments or live holds exist; expired holds are swept in the cancel transaction. Flyway V11 seeds `e2e.alice` / `e2e.bob`. |

## 5. AI mistakes observed

Only mistakes that actually occurred, recorded by the AI agent when they were found. Whether any
item in §4.1 counts as an AI mistake (rather than a design choice the owner changed) is for
the human owner to classify; none of those are entered here.

| Date | Phase / PR | Mistake | How it was caught | Correction |
|---|---|---|---|---|
| 2026-11-02 | Phase 1 / PR #2 (schema) | The AI-written `payment_attempt_failure_reason_ck` accepted a `DECLINED` attempt with a `NULL` reason: `NULL IN (…)` makes the CHECK evaluate to `NULL`, which PostgreSQL treats as passing. | Before commit, the AI agent ran the `DatabaseSchemaIT` assertions against a throwaway local PostgreSQL 16 (Docker was unavailable). The test `aDeclineMustRecordAReason` failed. | Added an explicit `failure_reason IS NOT NULL` to the CHECK in the not-yet-published V5 migration; the test then passed. Not yet reviewed by a human. |
| 2026-11-02 | Phase 2 (seat holds) | The AI agent first wrote the seat and hold locks as JPA `@Lock(PESSIMISTIC_WRITE)`, assuming Hibernate emits `FOR UPDATE`. On PostgreSQL it emits `FOR NO KEY UPDATE`. | The AI agent ran the app against local PostgreSQL with SQL logging and read the generated statements. | Rewrote the locking reads as native `SELECT … FOR UPDATE` so the lock clause matches the design and is visible in code. Not yet reviewed by a human. |
| 2026-11-02 | Phase 2 (seat holds) | A code comment claimed that without an explicit flush, the replacement hold's INSERT would hit the one-active-hold index before the old hold's UPDATE. Removing the flush did not fail any test: the next native query already auto-flushes. | Mutation check by the AI agent: removed the flush and re-ran `HoldApiIT` on local PostgreSQL; all tests still passed. | Kept the explicit flush (so correctness does not rely on implicit auto-flush) and corrected the comment. No test currently distinguishes the two; noted in the PR. |
| 2026-11-02 | Phase 3 (payment) | The first draft of the confirmation transaction returned "nothing to do" when the attempt was already settled. If a stale-`PENDING` resolution had recorded `DECLINED` (`NOT_RECEIVED`) while the charge was still in flight, the late capture would have been kept without a booking. | The AI agent's own review of the settled-attempt branch before writing tests. | A capture for an attempt that is settled but not `SUCCEEDED` is now voided. **This was incomplete:** it depended on the request thread surviving and the void succeeding. Human review of PR #4 found the underlying race (§4.2); it is now prevented by fencing the key. |
| 2026-11-02 | Phase 3 (payment) | The first concurrent-poll test only counted bookings. With the hold lock removed from the confirmation transaction, a losing poll saw no `HELD` seats, treated the converted hold as invalid, and **voided the charge of a confirmed booking**. The test still passed. | Mutation check by the AI agent: replaced the hold lock with a plain read and re-ran `PaymentConcurrencyIT` on local PostgreSQL. | Added assertions that no void is issued and the ledger stays `CAPTURED`; the mutation now fails the test in 3 of 3 runs. Not yet reviewed by a human. |
| 2026-11-02 | Task 06 (admin showtimes) | The AI agent relied on the `showtime_no_overlap_ex` exclusion constraint alone for concurrent showtime creation, assuming the losers would get an exclusion violation. Concurrent overlapping inserts instead **deadlocked** on each other's GiST index entries. The aborted request was reported as `409 SEATS_BUSY`, an unrelated error code. No overlapping showtime was stored. | The latch-started `AdminShowtimeApiIT.concurrentOverlappingCreatesProduceExactlyOneShowtime` failed with "deadlock detected" on the first Testcontainers run. | The creating transaction now locks the `screen` row first, with a bounded `lock_timeout` (409 `SCREEN_BUSY` when exceeded). Losers then get `SHOWTIME_OVERLAP`. The test passed in 5 of 5 repeated runs. Not yet reviewed by a human. |
| 2026-11-02 | Task 07 (Docker) | The backend image build failed before any Maven step: without `unzip` in `eclipse-temurin:21-jdk`, the Maven wrapper downloaded the `.tar.gz` distribution, which failed the `.zip` checksum pinned in `maven-wrapper.properties`. | The first `docker compose up --build` run by the AI agent. | `9e4d960` installs `unzip` in the build stage, keeping checksum validation. |
| 2026-11-02 | Task 07 (React) | `ScrollToTop` returned `window.scrollTo()` from an expression-bodied effect. Current Chrome returns a Promise from `scrollTo`, which React then called as the effect cleanup, crashing the app on every route change. Card posters were also blank because of a positioning class conflict. | The AI agent's headless-Chrome flow script against the Compose stack (the crash appeared in the captured console stack trace). | `1598feb`: block-bodied effects, a `fill` prop on `Poster`, and other layout fixes found in the same runs. |
| 2026-11-02 | Task 07 (checkout) | A fresh 8-minute hold displayed as "8:01" because the countdown rounded up and sub-second clock skew pushed it over. Separately, a 401 during checkout unmounted the checkout page, so after re-login a retry would have minted a **new** idempotency key for an attempt whose outcome might be unknown. | "8:01": the headless flow script's captured countdown text. The 401 issue: the AI agent's own review of the re-login path. | `c25f062`: the countdown rounds down, and checkout stays mounted during re-login so the open attempt keeps its key. |
| 2026-11-02 | Task 08 (Playwright) | The first fixture looked up Starlight's screen with a dateless search, which defaults to today; today had no seeded showtimes, so both tests failed. | The first Playwright run. | The fixture scans the next 14 days. The query parameter in that fix was wrong (`city`), which the human found in review (§4.3). |
| 2026-11-02 | Task 08 (Playwright) | The decline test clicked a showtime within the discover page's 300 ms search debounce, and the late search update replaced the navigation. This is an **app race**, not only a test problem. | The second Playwright run (1 of 2 failed). | The test waits for the search to apply before clicking. The app race was **not fixed** then (out of scope) and was listed in the README known limitations. It was fixed in Task 10 (below). |
| 2026-11-02 | Task 09 (audit) | The README's example search `cityId=1&date=2026-11-03&q=Intersteller` returned no results (Interstellar was not showing in Dallas that day). The architecture status table still said holds and payments were "Testcontainers not run", although they had passed on Testcontainers since Task 06. The design API table lacked `HOLD_CONVERTED`. | The AI agent ran every documented example against the clean stack during the audit. | README example changed to `cityId=2`; status rows corrected; `docs/api.md` lists the codes found in the code. |
| 2026-11-03 | Task 10 (search race) | The `MessagePort` override in the spec did not type-check. Playwright strips types, so the tests ran, but the frontend Docker build, which runs `tsc -b` over `e2e/`, failed. | The first `docker compose up -d --build` after the spec was written. | Typed `this: MessagePort` and the message argument; the image built. |
| 2026-11-03 | Task 10 (history API) | The same-instant tie-break test computed the expected order with Java's `UUID.compareTo`, which compares signed longs. PostgreSQL orders `uuid` by unsigned bytes, so the expectation could disagree with a correct query. | Found by the AI agent while getting `BookingHistoryApiIT` to pass on Testcontainers; whether a failing run or reading the code came first was not recorded. | The test compares the lowercase id strings, which order the same way as PostgreSQL. |
| 2026-11-03 | Task 10 (My Tickets) | On a phone-width screen, the card's poster strip cropped the movie monogram to its bottom edge. | Throwaway Playwright screenshots (390 px and 1280 px wide) taken by the AI agent, not committed. | The poster is shown from the `sm` breakpoint up; the card's title already names the movie. |
| 2026-11-03 | Task 11 (context without DB) | `VenueService` depends on the new `TheaterRepository`. `ApplicationContextWithoutDatabaseTest` did not mock it, so the Spring context failed to start (28 errors, the rest skipped by the failure threshold). | `./mvnw -B clean verify` after the admin package was added. | `TheaterRepository` added to `@MockitoBean`; `GET /api/languages` added to the anonymous browsing assertions. 28/28 then passed. |
| 2026-11-03 | Task 11 (layout lock) | A layout replacement that skipped `lockForScheduling` could interleave with the first showtime's `show_seat` insert. | Mutation check: the race test failed 3/3 with `CannotAcquireLockException` on the insert. | The lock was restored; with it, `AdminTheaterApiIT` is 16/16. |
| 2026-11-03 | Task 11 (admin date) | The dashboard used `schedule.data?.date ?? date ?? today` with `date = ''` when the URL had no date. `??` does not skip `''`, so `longDate('')` threw `RangeError: Invalid time value` and unmounted the app after admin login. | Playwright `admin.spec.ts` against the Compose stack; a debug run captured the pageerror. | Treat a missing date as `undefined` so today is used until the schedule arrives. |
| 2026-11-03 | Task 12 (cancel IT compile) | `ShowtimeCancellationIT` called HoldFixtures helpers that were package-private. | First `./mvnw -B verify` compile of the new IT. | Those helpers were made `public`. |
| 2026-11-03 | Task 13 (test YAML) | A test `application.yml` replaced the main YAML document and dropped `booking.lock-timeout`. | `ApplicationContextWithoutDatabaseTest` failed 28/28 to load the context. | Overlay `booking.outbox.enabled=false` with `src/test/resources/application.properties` instead. |

## 6. Task prompts and context (Tasks 06–13)

The prompts below were given by the human owner to one Cursor cloud agent (Claude) working in a
VM with a clone of this repository. Earlier phases (PRs #1–#4) were done by a different agent
session whose prompts are not available here; their findings are recorded in §4.1, §4.2 and §5.
The prompts are summarized, and constraints are quoted where they shaped the result.

| Task | Prompt summary | Key constraints given |
|---|---|---|
| 06: discovery and showtime APIs (PR #6) | Inspect `main` and the docs, confirm PR #4 is present, check for existing Task 06 work, then implement public cities, movies, showtime search (`pg_trgm`, pagination, local dates) and detail, an anonymous seat map, and theater-scoped admin showtime creation with overlap protection. | "Preserve existing architecture and Flyway migration history." "Do not implement React in this task." "Report Docker/Testcontainers checks accurately if unavailable." "Never merge, squash, amend, force-push or rewrite history." |
| 07: Cinéva React app (PR #7) | Build a polished React + TypeScript + Vite customer app (discover, movie, seats, login, checkout, ticket) on the real endpoints. Add a frontend Docker service with a same-origin proxy. | "No fake booking confirmations or hardcoded seat availability." "Do not automatically retry a charge with a fresh key after an ambiguous result." "Store credentials in memory only." "Do not implement Playwright yet." "State honestly what was and wasn't verified." |
| 08: Playwright E2E (PR #8) | A reliable Playwright suite against the Compose app: a successful booking, and a decline followed by a retry with a new key, run twice against a clean database. | "Use data-testid selectors." "No arbitrary sleeps." "Tests must be independent and use fresh/resettable booking data." "Never use already-booked seats." "Do not disable tests or hide failures." |
| 08 follow-up (PR #8) | Fix the `city` → `cityId` parameter found in human review, add an assertion that the filter applies, check the other parameters, and run the suite twice. | "Make one small descriptive fix commit." "Do not start additional feature work." |
| 09: final audit (this PR) | Requirements checklist, accurate docs from the actual code, Git and secret audit, clean-start verification of every suite, README walkthrough from a clean checkout. | "Do not invent functionality or results." "Do not claim production scalability without benchmarks." "Do not invent elapsed engineering hours." "Do not modify existing committed Flyway migrations." |
| 10: search race fix and My Tickets | Reproduce the search debounce race in a deterministic Playwright test that fails before the fix, fix the cause, and keep Back/Forward, filters and accessibility. Add `GET /api/bookings` (paginated, newest first, the customer from the principal, no denormalization, indexes only in a new migration) and a My Tickets screen with loading, empty and error states. | "Fix the underlying issue, not the test timing." "No arbitrary sleeps or timing workarounds." "Never from a user-supplied customerId." "No passwords or bookings in browser storage." "Do not run docker compose down -v." "STOP after Task 10." |
| 11: theater administration | Complete theater admin: assigned theaters only, screens and seating plans, creating a showtime, service-layer authorization, a Cinéva admin dashboard, Testcontainers coverage including cross-theater 403 and a showtime-creation race, frontend form unit tests, and a Playwright path from admin login through public discovery. | "Never modify or delete seats belonging to existing showtimes." "Never run docker compose down -v or delete volumes." "Do not reset or alter existing booked seats." "Do not implement cancellation or outbox processing." "STOP after Task 11." |
| 12: showtime cancellation and hold lookup | Authorized cancel with lock-order-safe refusal of bookings, in-flight payments and live holds; `GET /holds/{id}`; Playwright customers `e2e.alice` / `e2e.bob`; dashboard Cancel control. | "Never run docker compose down -v." "Never modify previously committed Flyway migrations." "Do not persist passwords." "STOP after Task 12." |
| 13: transactional outbox delivery | In-process dispatcher with SKIP LOCKED, idempotent inbox, retry/backoff, crash recovery tests; no Kafka. | "Never run docker compose down -v." "Never modify previously committed Flyway migrations." "STOP after Task 13." |

Task 12 was done by a local Cursor agent on the owner's Mac against the owner's running
Compose stack, after PR #11 had been merged to `main`.

Task 10 was done by a local Cursor agent (Claude) on the owner's Mac against the owner's running
Compose stack. Its Maven runs used the installed JDK 23 through `JAVA_HOME`, because the default
`java` was JDK 17; nothing was installed system-wide.

Context the agent worked from: `AGENTS.md`, the docs in `docs/`, the code, and the running stack.
For Tasks 06–09, its environment needed two VM-only fixes, neither of them committed:
- installing `docker-compose-v2`;
- `sudo iptables-legacy -P FORWARD ACCEPT`, because a stale legacy `FORWARD DROP` policy blocked
  container-to-container traffic. Re-confirmed in the audit.

## 7. Verification performed (Tasks 06–13)

| Task | Commands run by the AI agent | Result |
|---|---|---|
| 06 | `./mvnw -B clean verify` (the first full run on Testcontainers) | 59/59 unit tests, 132/132 integration tests |
| 07 | `npm test`; `npm run build`; `docker compose up --build`; headless-Chrome flows (success after decline, timeout polling, seat conflict, admin refused, hold expiry); `./mvnw -B clean verify` | All passed, after the fixes in §5 |
| 08 | `npm test`; `npm run build`; `npx playwright test --repeat-each=5`; `docker compose down -v` + `up --build`; `npm run test:e2e` twice; `./mvnw -B clean verify` | 34/34; build OK; 15/15; healthy; 3/3 twice; 59/59 + 132/132 |
| 08 follow-up | `npx tsc -b`; `npm run test:e2e` twice | OK; 3/3 twice |
| 09 | The clean start, every suite, and the README steps in a fresh clone (details in `docs/test-strategy.md` and `docs/requirements-checklist.md` §10) | All passed |
| 10 | The race test against the unfixed app, then with the fix (`--repeat-each=10`); `./mvnw -B clean verify`; `npm test`; `npm run build`; `docker compose up -d --build` (no `down -v`); `npx playwright test`, then with `--repeat-each=3`; `./run-tests.sh` | Failed 5/5, then 20/20; 64/64 + 142/142; 40/40; build OK; healthy, V10 applied, the hand-made booking kept; 6/6, then 18/18; exit 0 |
| 11 | `./mvnw -B verify -Dit.test=AdminTheaterApiIT`; mutation of `replaceLayout` without the screen lock (3 runs); `./mvnw -B clean verify`; `npm test`; `npm run typecheck`; `docker compose up -d --build` (no `down -v`); `npx playwright test`, then `--repeat-each=3`; `./run-tests.sh` | 16/16; failed 3/3 without the lock; 64/64 + 158/158; 50/50; typecheck OK; healthy, booking `9956RB4M` kept; 8/8, then 24/24; exit 0 |
| 12 | `./mvnw -B clean verify`; `npm test`; `npm run build`; `docker compose up -d --build --wait` (no `down -v`); `npx playwright test`, then `--repeat-each=3`; `./run-tests.sh` | 64/64 + 174/174; 52/52; build OK; healthy, V11 applied, booking `9956RB4M` kept; 9/9, then 27/27; exit 0 |
| 13 | `./mvnw -B clean verify`; `npm test`; `npm run build`; `docker compose up -d --build --wait` (no `down -v`); `npx playwright test`; `./run-tests.sh` | 66/66 + 184/184; 53/53; build OK; healthy, V12 applied, booking `9956RB4M` kept; 9/9; exit 0 |

A human re-run of these commands is not recorded in this document.

## 8. Reflections

_Placeholder for the human owner's own reflections._ Factual observations from the entries above:

- Several real defects were caught only by **running** things rather than by reading code: the
  GiST deadlock, the Hibernate lock clause, the Maven checksum failure, the route-change crash and
  the README example.
- **Mutation checks** (deliberately breaking the code to see if a test fails) exposed two tests
  that passed for the wrong reason (§5).
- **Human review** caught a defect that every automated run missed: the ignored `city` parameter
  passed because the screen name happened to be unique.
