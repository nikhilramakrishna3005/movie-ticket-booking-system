# Requirements Checklist

> **Status: FINAL AUDIT, 2026-11-02; updated for Task 10 on 2026-11-03.** This maps every requirement in `AGENTS.md` (the assignment
> constraints) and every acceptance criterion in `docs/product-scope.md` to the code, tests and
> documentation that address it. Status values:
> - **Complete:** implemented and covered by a passing automated test, or verifiable in the code.
> - **Partial:** some of it is implemented; the gap is stated.
> - **Not implemented:** not built, and stated as such.
>
> The test results referred to are from the Task 10 run in `docs/test-strategy.md`; §10 also
> keeps the final-audit results of 2026-11-02.

## 1. Project goals (`AGENTS.md` §1)

| Requirement | Status | Implementation | Evidence |
|---|---|---|---|
| No seat is ever sold twice | Complete | Ordered `FOR UPDATE` locks in `HoldService` and `CheckoutTransactions`; `booking_seat UNIQUE (showtime_id, seat_id)` | `HoldConcurrencyIT`, `PaymentConcurrencyIT`, `DatabaseSchemaIT.aSeatCannotBeBookedTwiceForTheSameShowtime`, E2E decline-retry test |
| An expired hold is never converted into a booking | Complete | TX3 validity check under lock with `clock.instant()`; void path in `CheckoutService` | `PaymentApiIT.captureArrivingAfterHoldExpiryIsVoidedEvenThoughTheSeatsAreFree`, `…unknownPaymentResolvedAfterHoldExpiryIsVoidedAndBooksNothing` |
| Behavior proven by automated tests, especially concurrency | Complete | 64 + 142 backend, 40 frontend unit and 6 E2E tests, all passing | `docs/test-strategy.md` |
| Explicit tradeoffs | Complete | ADRs 0001–0003; architecture §6.7 (provider limits), §7 (multi-instance clock), §12 (scale); README known limitations | — |
| Clear module ownership | Complete (by convention) | Packages `catalog`, `theater`, `booking`, `payment`, `admin`, `security` (plus `config`) | Enforced by review only; no ArchUnit test (as `AGENTS.md` §3 allows) |
| 10-hour budget | Not tracked | — | Time was not tracked precisely, so no actual hours are claimed (`docs/implementation-plan.md` §6) |

## 2. Technology constraints (`AGENTS.md` §2)

| Requirement | Status | Implementation / evidence |
|---|---|---|
| Java 17+, Spring Boot, REST/JSON | Complete | Java 21, Spring Boot 3.5 (`backend/pom.xml`); 14 endpoints (`docs/api.md`) |
| PostgreSQL as the authoritative seat owner | Complete | `show_seat`, `seat_hold` and `booking_seat` in PostgreSQL 16; no cache |
| Flyway migrations, no `ddl-auto` generation | Complete | V1–V10; `spring.jpa.hibernate.ddl-auto: validate`; `DatabaseSchemaIT.allMigrationsApplyInOrder` |
| React frontend | Complete | React 19 + TypeScript + Vite (`frontend/`) |
| Docker Compose, whole system local, no cloud dependencies | Complete | `docker-compose.yml` (postgres, backend, frontend). After the images are built, no hosted service is used. Building downloads base images and Maven and npm packages once. |
| JUnit 5, Spring Boot integration tests, Testcontainers PostgreSQL | Complete | 142 `*IT` tests on `postgres:16-alpine` |
| Playwright, at least 2 tests | Complete | 6 tests in `frontend/e2e/` |
| Simulated in-process payment (success, failure, timeout) | Complete | `SimulatedPaymentProvider` with its own ledger table; scenarios `SUCCESS` / `DECLINE` / `TIMEOUT` |
| Spring Security, HTTP Basic, seeded demo users | Complete | `SecurityConfig`; V8 BCrypt users |
| Injectable UTC `Clock` for all expiry logic | Complete | `ClockConfig`. No `Instant.now()`, `LocalDateTime.now()` or SQL `NOW()` in `backend/src/main` (checked with `rg`). Tests use `MutableClock`. |
| Redis, Kafka, Solr deferred with ADRs | Complete | None of them is in `pom.xml`, `package.json` or `docker-compose.yml`. ADRs 0001–0003 were accepted by the human owner on 2026-11-02. |

## 3. Module boundaries (`AGENTS.md` §3)

| Requirement | Status | Notes |
|---|---|---|
| `controller → service → repository`, no speculative interfaces | Complete | No interfaces with a single implementation; `SimulatedPaymentProvider` is a concrete class |
| Packages call each other's services, not repositories; each writes only its own tables | Complete (by review) | The documented exceptions are the `catalog` read-only search SQL and the payment-attempt update inside TX3 (architecture §3). `PaymentController` lives in `booking` because it orchestrates holds and bookings. |
| Only `booking` changes seat ownership | Complete | `theater` only inserts the initial `AVAILABLE` rows for a new showtime (documented exception) |
| Provider never called inside a transaction, enforced by a guard | Complete | `TransactionSynchronizationManager.isActualTransactionActive()` guard; `PaymentApiIT.providerRefusesToRunInsideATransaction`, `…providerIsOnlyEverCalledOutsideADatabaseTransaction` |

## 4. Coding standards (`AGENTS.md` §4)

| Requirement | Status | Evidence |
|---|---|---|
| Short service-layer transactions; no network, sleep or payment calls inside them | Complete | Provider guard and the tests above |
| Every schema change is a new migration; committed migrations are unchanged | Complete | V1–V10. Task 10 added V10 and modified no committed migration. |
| Database constraints as backstops | Complete | `docs/data-model.md` constraint table; 33 `DatabaseSchemaIT` tests |
| Global lock order and bounded `lock_timeout` | Complete | `set_config('lock_timeout', …)` in `ShowSeatRepository` and `ScreenRepository`; `HoldConcurrencyIT.lockWaitIsBoundedAndReportedAsSeatsBusyWithNothingHeld`, `…overlappingMultiSeatRequestsNeverDeadlockOrPartiallySucceed` |
| Frontend: functional components, one API client module, stable `data-testid` | Complete | `frontend/src/api/client.ts`; `data-testid` used by all E2E selectors |

## 5. Test expectations (`AGENTS.md` §5)

| Requirement | Status | Tests |
|---|---|---|
| Unit tests for pure business rules | Complete | `HoldRulesTest`, `PaymentAttemptRulesTest`, `ShowtimeSchedulingRulesTest`, `ShowtimeSearchTest`, `AccessPolicyTest`, `BookingPageRequestTest`; frontend `paymentFlow.test.ts`, `selection.test.ts`, `history.test.ts` |
| Testcontainers integration tests for DB, transactions and locking (no H2) | Complete | All 142 `*IT` tests |
| Concurrency: many threads, exactly one winner per seat | Complete | `HoldConcurrencyIT` (5), `PaymentConcurrencyIT` (4), `AdminShowtimeApiIT.concurrentOverlappingCreatesProduceExactlyOneShowtime` |
| Payment success, decline and timeout each tested | Complete | `PaymentApiIT.successfulPaymentConfirmsOneBookingAndBooksTheSeats`, `…declineCreatesNoBookingAndARetryNeedsANewKey`, `…timeoutIsUnknownUntilAPollFindsTheCaptureAndConfirms` |
| Idempotent replay: charge count stays 1 | Complete | `PaymentApiIT.replayingAKeyReturnsTheSameBookingWithoutChargingAgain`, `PaymentConcurrencyIT.concurrentRequestsWithTheSameKeyChargeOnceAndBookOnce` (assert `simulated_provider_charge` count is one) |
| Unknown outcomes never treated as declined | Complete | `PaymentApiIT.timeoutIsUnknown…`, `…holdWithUnknownPaymentCannotBeReleased`; frontend `paymentFlow.test.ts` "treats UNKNOWN as pending, never as declined" |
| Late capture after hold expiry is voided, no booking | Complete | `PaymentApiIT.captureArrivingAfterHoldExpiryIsVoidedEvenThoughTheSeatsAreFree` |
| Authorization: 401, 404 for others' holds, 403 theater-scoped admin | Complete | `SecurityIT`, `HoldApiIT.anotherCustomersHoldLooksNonexistentAndIsNotReleased`, `PaymentApiIT.customersCannotPayForOrSeeAnotherCustomersHoldPaymentOrBooking`, `AdminShowtimeApiIT.onlyAdminsAssignedToTheScreensTheaterMayCreateShowtimes`, `BookingHistoryApiIT` (401, admin 403, Alice never sees Bob's bookings) |
| Playwright: successful booking, plus a failure path | Complete | `booking.spec.ts` (success; decline then retry); `seat-conflict.spec.ts`; Task 10: `search-navigation.spec.ts`, `my-tickets.spec.ts` |
| Deterministic tests: mutable clock, latches, no wall-clock sleeps | Complete | `MutableClock`, `CountDownLatch` in the concurrency ITs. E2E uses only Playwright auto-waiting, with no fixed sleeps. The search race test controls the debounce with Playwright's fake clock and holds React's render scheduler explicitly. |
| No failing test deleted or disabled | Complete | 0 skipped in every suite |

## 6. Working rules, Git and secrets (`AGENTS.md` §6)

| Requirement | Status | Evidence (audit 2026-11-02) |
|---|---|---|
| Never push or merge to `main` (humans merge) | Complete | All 7 changes reached `main` as merge commits of reviewed PRs #1–#4 and #6–#8 |
| No squash, rebase, amend or force-push; history preserved | Complete as far as the local history can show | `origin/main` has 35 commits. Every PR's individual commits are reachable as the second parent of its merge commit. |
| No secrets in the repository | Complete | A history-wide scan for private keys, cloud and GitHub tokens, and API-key patterns found none. Only `.env.example` (placeholders) is tracked; `.env` is git-ignored and was never committed. |
| Local credentials via environment variables, with `.env.example` placeholders | Complete | `docker-compose.yml` requires `POSTGRES_*` from `.env`; `application.yml` reads `DB_*` with no defaults |
| Demo users public, local-only and labeled | Complete | README "Demo accounts (fictional, local only)"; V8 comments. The passwords appear only in the README, V8 comments, backend tests and E2E fixtures. |
| React holds no credentials and stores none | Complete | No `localStorage`, `sessionStorage`, `indexedDB` or `document.cookie` in `frontend/src`; no demo password in `frontend/src` or the built bundle; `client.test.ts` covers in-memory credentials; `my-tickets.spec.ts` asserts local and session storage and cookies are empty after viewing tickets |
| Source, migrations, frontend and tests tracked | Complete | `git ls-files`: `backend/src/main` (Java and V1–V10), `backend/src/test`, `frontend/src`, `frontend/e2e`, Dockerfiles, Compose, docs. Build outputs and reports are ignored. |

## 7. Acceptance criteria (`docs/product-scope.md` §5)

| ID | Status | Test(s) |
|---|---|---|
| AC-S1 city and date | Complete | `DiscoveryApiIT.cityAndDateReturnOnlyThatCitysShowtimesOnThatLocalDate` |
| AC-S2 language and genre filters | Complete | `DiscoveryApiIT.combinedFiltersNarrowTheResultAndGenreIsCaseInsensitive` |
| AC-S3 typo tolerance | Complete | `DiscoveryApiIT.misspelledMovieTitleStillFindsTheMovie`, `DatabaseSchemaIT.trigramSearchToleratesTypos` |
| AC-S4 empty result is 200 | Complete | `DiscoveryApiIT.textSearchCombinesWithOtherFiltersAndUnrelatedTextFindsNothing` |
| AC-S5 pagination, capped size | Complete | `DiscoveryApiIT.resultsArePaginatedWithoutGapsOrDuplicates`, `…pageSizeIsCappedAndInvalidPagingIsRejected` |
| AC-H1 hold with clock-based expiry | Complete | `HoldApiIT.holdsSeatsAndReturnsIdSortedSeatsAndClockBasedExpiry` |
| AC-H2 all-or-nothing | Complete | `HoldApiIT.alreadyHeldSeatConflictsAndMultiSeatRequestHoldsNothing`; E2E `seat-conflict.spec.ts` |
| AC-H3 one winner under contention | Complete | `HoldConcurrencyIT.manyCustomersRacingForOneSeatProduceExactlyOneHolder` |
| AC-H4 no deadlock on overlapping requests | Complete | `HoldConcurrencyIT.overlappingMultiSeatRequestsNeverDeadlockOrPartiallySucceed` |
| AC-H5 bounded lock wait gives `SEATS_BUSY` | Complete | `HoldConcurrencyIT.lockWaitIsBoundedAndReportedAsSeatsBusyWithNothingHeld` |
| AC-H6 expiry without a background job | Complete | `HoldApiIT.expiredHoldStopsBlockingExactlyAtExpiry` |
| AC-H7 input validation | Complete | `HoldApiIT.duplicateSeatIdsAreListed`, `…unknownSeatsAndSeatsOfAnotherScreenAreRejectedWithNothingHeld`, `…tenSeatsIsTheMaximumThatCanBeHeld` |
| AC-H8 one active hold per showtime | Complete | `HoldApiIT.customerHasAtMostOneActiveHoldPerShowtime`, `HoldConcurrencyIT.sameCustomerRacingTwoHoldsForOneShowtimeGetsExactlyOne` |
| AC-H9 no holds on started or cancelled shows | Complete | `HoldApiIT.startedOrCancelledShowtimeCannotBeHeld` |
| AC-H10 release, ownership, blocked during payment | Partial | Release, 404 for others and the in-flight block are tested (`HoldApiIT`, `PaymentApiIT.holdCannotBeReleasedOrPaidAgainWhileAPaymentIsInFlight`). The "view a hold" endpoint (`GET /holds/{id}`) is not implemented. |
| AC-B1 success: one booking, seats booked, outbox event | Complete | `PaymentApiIT.successfulPaymentConfirmsOneBookingAndBooksTheSeats` |
| AC-B2 decline: no booking, hold stays | Complete | `PaymentApiIT.declineCreatesNoBookingAndARetryNeedsANewKey`; E2E decline test |
| AC-B3 retry with a new key; refused near expiry | Complete | Same test; `PaymentApiIT.paymentNeedsAnActiveHoldWithEnoughTimeLeft` |
| AC-B4 same key never charges twice | Complete | `PaymentApiIT.replayingAKey…`, `PaymentConcurrencyIT.concurrentRequestsWithTheSameKey…` |
| AC-B5 expired hold never converted | Complete | `PaymentApiIT.captureArrivingAfterHoldExpiryIsVoided…` |
| AC-B6 timeout is `UNKNOWN`, resolved by the ledger | Complete | `PaymentApiIT.timeoutIsUnknown…`, `…stalledChargeArrivingAfterAStaleResolutionCannotCapture`, `SimulatedProviderFenceIT` |
| AC-B7 no second attempt while one is in flight | Complete | `PaymentApiIT.holdCannotBeReleasedOrPaidAgainWhileAPaymentIsInFlight` |
| AC-B8 DB rejects a duplicate booked seat | Complete | `DatabaseSchemaIT.aSeatCannotBeBookedTwiceForTheSameShowtime` |
| AC-B9 provider refuses inside a transaction | Complete | `PaymentApiIT.providerRefusesToRunInsideATransaction` |
| AC-U1 401 without credentials | Complete | `SecurityIT.missingCredentialsAreRejected`, `DiscoveryApiIT.anonymousVisitorsBrowseSeatMapsButMustLogIn…` |
| AC-U2 admin scoped to assigned theaters | Complete | `AdminShowtimeApiIT.assignedAdminCreatesShowtime…`, `…onlyAdminsAssignedToTheScreensTheaterMayCreateShowtimes` |
| AC-U3 customers can't use admin endpoints; admins can't hold | Complete | `SecurityIT.customerIsForbiddenFromAdminEndpoints`, `ApplicationContextWithoutDatabaseTest.holdEndpointsAreForCustomersOnly` |
| AC-U4 overlapping showtimes rejected | Complete | `AdminShowtimeApiIT.overlappingShowtimeOnTheSameScreenIsRejected`, `DatabaseSchemaIT.scheduledShowtimesCannotOverlapOnTheSameScreen` |
| AC-E1 E2E successful booking | Complete | `booking.spec.ts` "a customer books seats…" |
| AC-E2 E2E decline | Complete | `booking.spec.ts` "a declined payment books nothing…" |

## 8. MVP workflows (`docs/product-scope.md` §3)

| Workflow | Status | Notes |
|---|---|---|
| 3.1 Search showtimes | Complete | API and UI. Results are ordered by relevance, then time. Task 10 fixed a race where opening a showtime during the search debounce sent the user back to Discover (`search-navigation.spec.ts`). |
| 3.2 View seat map | Complete | Effective status with lazy expiry; the UI refreshes it every 15 s |
| 3.3 Hold seats | Complete | 1–10 seats, 8-minute TTL, one active hold per showtime |
| 3.4 Pay and confirm | Complete | Success, decline with retry, timeout with polling, late-capture void, at most 3 attempts |
| 3.5 Abandonment and release | Complete | Lazy expiry; release blocked while a payment is in flight |
| 3.6 View my bookings | Complete (Task 10) | `GET /api/bookings` (own confirmed bookings, newest first, paginated) and the My Tickets screen at `/tickets`. `BookingHistoryApiIT`, `history.test.ts`, `my-tickets.spec.ts`. |
| 3.7 Admin theaters, screens and showtimes | Complete (Task 11) | Assigned-theater APIs, screen add / layout replace-before-first-showtime, `/admin` dashboard. `AdminTheaterApiIT`, `forms.test.ts`, `admin.spec.ts`. Showtime cancellation is still not implemented. |

## 9. Required documentation (`docs/implementation-plan.md` Phase 7)

| Document | Status | Location |
|---|---|---|
| README: overview, prerequisites, one-command start, demo accounts, screenshots, tests, structure, limitations | Complete | `README.md` |
| Product scope and deferred features | Complete | `docs/product-scope.md` (§4 non-goals, §8 delivery status) |
| Roles and permissions matrix | Complete | `docs/architecture.md` §8.1 (marked as implemented or not) |
| Architecture diagram and booking/payment flow | Complete | `docs/architecture.md` §2, §6.3, §6.4 |
| ERD matching V1–V10 | Complete | `docs/data-model.md` |
| API reference | Complete | `docs/api.md` |
| Redis, Kafka and Solr ADRs | Complete (accepted 2026-11-02) | `docs/decisions/0001`–`0003` |
| Test strategy | Complete | `docs/test-strategy.md` |
| AI workflow record | Complete | `docs/ai-workflow.md` |

## 10. Final verification and reproducibility

| Check | Result (2026-11-02, Cursor cloud agent VM) |
|---|---|
| Clean start: `docker compose down -v` then `docker compose up --build -d --wait` | All three services healthy |
| Health through the proxy | `{"status":"UP"}` |
| Backend: `./mvnw -B clean verify` | 59/59 unit tests, 132/132 integration tests, BUILD SUCCESS |
| Frontend: `npm test`; `npm run build` | 34/34 pass; build succeeds |
| Playwright on the clean stack, two runs | 3/3, then 3/3 |
| Single runner: `./run-tests.sh` against the running stack | Exit 0: backend 59/59 unit and 132/132 integration, frontend 34/34, Playwright 3/3, no skipped tests. It exits 1 on a failing test, a skipped unit or E2E test, or an unhealthy stack (each checked once). |
| Clean checkout following only the README | A fresh `git clone` (no `.env`) ran `cp .env.example .env && docker compose up --build -d --wait` and became healthy. In the clone, `npm ci`, `npm test` (34/34), `npm run build`, `npx playwright install --with-deps chromium` and `npm run test:e2e` (3/3 on two runs) passed, and `cd backend && ./mvnw -B clean verify` passed (59/59 unit, 132/132 integration, BUILD SUCCESS). Docker's build cache was warm from earlier builds in the same VM, so the startup time (~27 s) does not reflect a cold build. |
| Hosted services needed at runtime | None. Payment is in-process, and fonts and posters are bundled or generated locally. |
| Non-portable environment assumptions | The agent VM needed `sudo iptables-legacy -P FORWARD ACCEPT` once, because a stale legacy `FORWARD DROP` policy blocked container-to-container traffic. This was re-confirmed during the audit: with DROP restored, nginx could not reach the backend. It is a VM property, not a project requirement, and is documented in the README troubleshooting section. Host ports 3000 and 5432 must be free or overridden in `.env`. |

**Task 10 (2026-11-03, a Mac with Docker Desktop, the existing Compose stack and its database kept):**

| Check | Result |
|---|---|
| Backend: `./mvnw -B clean verify` (JDK 23; the build targets Java 21) | 64/64 unit tests, 142/142 integration tests, BUILD SUCCESS |
| Frontend: `npm test`; `npm run build` | 40/40 pass; build succeeds |
| Stack rebuilt in place: `docker compose up -d --build` (no `down -v`) | All three services healthy; Flyway applied V10; the existing manually made booking was still present |
| Playwright against the running stack | 6/6, then 18/18 with `--repeat-each=3` |
| Search race regression before the fix | Failed 5/5 runs; after the fix, 20/20 (10 repeats of the 2 search tests) |
| Single runner: `./run-tests.sh` against the running stack | Exit 0: backend 64/64 unit and 142/142 integration, frontend 40/40, Playwright 6/6, no skipped tests |

A clean `docker compose down -v` start was not run for Task 10, to keep the local database.

**Task 11 (2026-11-03, same Mac, existing Compose stack and database kept):**

| Check | Result |
|---|---|
| Backend: `./mvnw -B clean verify` (JDK 23) | 64/64 unit tests, 158/158 integration tests, BUILD SUCCESS |
| Frontend: `npm test`; `npm run typecheck` | 50/50 pass; typecheck succeeds |
| Layout-lock mutation | Race test failed 3/3 without the screen lock |
| Stack rebuilt in place: `docker compose up -d --build` (no `down -v`) | All three services healthy; booking `9956RB4M` still present (33 confirmed bookings) |
| Playwright against the running stack | 8/8, then 24/24 with `--repeat-each=3` |
| Single runner: `./run-tests.sh` against the running stack | Exit 0: backend 64/64 unit and 158/158 integration, frontend 50/50, Playwright 8/8, no skipped tests |

A clean `docker compose down -v` start was not run for Task 11, to keep the local database.

## 11. Remaining gaps (honest summary)

- Not implemented: `GET /holds/{id}`, showtime cancellation,
  background workers (outbox relay, cleanup, scheduled reconciliation).
- E2E tests do not cover timeout/`UNKNOWN` polling, hold expiry, keyboard-only use or mobile
  viewports; these are covered at the API or unit level, or not at all (`docs/test-strategy.md`).
- No load testing, so no scalability claim.
- The sample data, and the E2E screen lookup, stop working after 2027-01-31.
- Engineering time was not tracked precisely.
