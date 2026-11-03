# Product Scope

> **Status: TARGETS WRITTEN BEFORE IMPLEMENTATION; DELIVERY STATUS IN §8.** Sections 1–7 are the
> scope as planned for the 10-hour assessment build and are kept as written. What was actually
> delivered is summarized in §8 and mapped criterion by criterion, with tests, in
> `docs/requirements-checklist.md`.
>
> Revision 2 applies design decisions approved by the engineering owner during review of PR #1.

## 1. Product summary

A web application where customers find movie showtimes across many theaters and cities,
select specific seats, pay (simulated), and receive a confirmed booking. Theater administrators
create showtimes for the theaters they manage.

Design target (not a performance claim): 2,000 theaters across 300 cities, with traffic spikes
of up to 500,000 visitors in 10 minutes during major releases. See `docs/architecture.md` §12
for how the design accounts for this and what is out of reach for this assessment.

## 2. Roles and permissions

| Role | Description | MVP authentication |
|---|---|---|
| **Customer** | Searches showtimes, holds seats, pays, views own bookings. | Seeded demo user, HTTP Basic |
| **Theater Admin** | Creates showtimes for the theater(s) they are assigned to. | Seeded demo user assigned to specific theaters, HTTP Basic |
| Anonymous visitor | Can search and view showtimes and seat maps. Must log in to hold seats. | — |

- Authentication is **local-only for the assessment**: Spring Security HTTP Basic with seeded
  demo users. Demo credentials are non-secret, documented in the README, and must never be
  used in a real deployment. **HTTP Basic requires HTTPS in any real deployment.**
- The React app has a simple login form. Credentials are kept **in memory only**, never in
  `localStorage`, `sessionStorage`, or cookies. Refreshing the page requires logging in again.
- The movie catalog, cities, theaters, screens, and seat layouts are **seeded via migrations**.
  There is no platform-admin role.
- The full permission matrix is in `docs/architecture.md` §8.1.

## 3. MVP workflows (proposed)

### 3.1 Customer: search showtimes
1. Customer selects a city and date (defaults to today in the theater's local time zone).
2. Customer optionally filters by language and genre, and/or types a movie or theater name.
3. Search tolerates typos (e.g. "Intersteller" finds "Interstellar"; "Regall" finds "Regal").
4. Results list showtimes, ordered by relevance and then start time, paginated.

### 3.2 Customer: view seat map
1. Customer opens a showtime.
2. Each seat is shown as **available** or **unavailable** (held by someone else, or booked),
   with category and price. A hold that has passed its expiry time is shown as available,
   even if no cleanup has run.

### 3.3 Customer: hold seats
1. Customer (logged in) selects 1–10 distinct seats and clicks "Continue".
2. The system places a temporary hold on **all** selected seats or **none** of them.
3. On success the customer sees a checkout page with a countdown (proposed TTL: 8 minutes).
4. A customer may have **at most one active hold per showtime**. An expired hold does not
   count and is replaced automatically when the customer makes a new hold.

### 3.4 Customer: pay and confirm
1. Customer chooses a simulated payment outcome (success / decline / timeout) on the checkout
   page. Each click of "Pay" is a new payment action with a new idempotency key. Network
   retries and double-clicks of the same action reuse that key.
2. **Success** (hold still valid) → booking confirmed, seats permanently booked, booking
   reference shown, and a "booking confirmed" event recorded.
3. **Decline** → no booking. The customer may click "Pay" again (new key) while the hold is
   still valid and has enough time left, up to a limit of 3 attempts per hold (proposed).
4. **Timeout** → the outcome is **unknown**. The UI says "Payment status is being confirmed"
   and checks the status again. It never shows "declined" for an unknown outcome. The final
   result is success (booking), voided (no booking, money returned), or declined, but only if
   the provider confirms no charge was made.
5. **Success that arrives after the hold expired** → **no booking**, even if the seats are still
   free. The payment is voided (simulated refund) and the customer is told the hold expired
   and they were not charged.

### 3.5 Customer: abandonment and release
1. If the customer leaves checkout or lets the countdown expire, the hold expires and the
   seats become available to others, with no manual action and no background job required.
2. The customer may release the hold with "Cancel", **unless a payment is in progress or its
   outcome is unknown**. In that case the hold simply expires.

### 3.6 Customer: view my bookings
1. Customer sees their confirmed bookings (movie, theater, time, seats, reference).
   (Originally on the cut list; delivered in Task 10 as `GET /api/bookings` and the My Tickets
   screen.)

### 3.7 Theater Admin: theaters, screens and showtimes
1. An admin sees only the theaters they are assigned to, with each screen's seating plan and
   a day's showtimes (prices and booked/held counts).
2. An admin creates a showtime (movie, screen, start time, language, prices) **only for a
   theater they are assigned to**. The seat inventory is generated from the screen layout.
3. Overlapping showtimes on the same screen are rejected.
4. An admin may add a screen, and may replace a screen's layout only while it has never had a
   showtime (existing bookings and inventory keep their seats).
5. The admin dashboard is at `/admin`. An assigned admin may cancel a showtime that has no
   confirmed bookings, no unresolved payments and no active holds. Cancelled shows stay in the
   admin schedule and disappear from public search.

## 4. Explicit non-goals

- Real payment providers, real card data, PCI compliance, real refunds.
- Real identity management (sign-up, password reset, OAuth/SSO, MFA), and HTTPS in the local stack.
- Real email/SMS/push delivery. Booking events are recorded in an outbox; delivering them is a
  stretch item.
- Customer cancellations or refunds after a booking is confirmed.
- Platform-admin role; catalog management UI.
- Dynamic pricing, coupons, loyalty, food & beverage, gift cards, taxes, multi-currency, i18n.
- Recommendations, reviews, trailers.
- Background workers (hold-cleanup sweeper, outbox relay, scheduled payment reconciliation).
  These are stretch items only; correctness must not depend on them.
- Virtual waiting room, CDN, autoscaling, multi-instance deployment, load testing to the
  500,000-visitor target (discussed as design considerations only).
- Accessibility audit and mobile apps.

## 5. Acceptance criteria (proposed)

Each criterion should map to at least one automated test once implemented (see
`docs/implementation-plan.md` §4 for the proposed test mapping).

### Search
- **AC-S1** Searching by city and date returns only showtimes in that city on that date.
- **AC-S2** Language and genre filters narrow results correctly and can be combined.
- **AC-S3** A movie or theater name with a single-character typo still returns the intended match.
- **AC-S4** A search with no matches returns an empty result (HTTP 200).
- **AC-S5** Results are paginated; requested page size is capped.

### Seat holds
- **AC-H1** Holding available seats succeeds and returns a hold id and an expiry time computed
  from the injected clock.
- **AC-H2** A hold is all-or-nothing. If any requested seat is unavailable, no seat is held and
  the response (409 `SEATS_UNAVAILABLE`) lists the conflicting seats.
- **AC-H3** When N concurrent requests compete for the same seat, exactly one succeeds and the
  database shows exactly one holder.
- **AC-H4** Overlapping multi-seat requests submitted in different orders complete without
  deadlock. Each request either holds all its seats or none.
- **AC-H5** If a seat lock cannot be acquired within the bounded lock wait, the request fails
  with 409 `SEATS_BUSY` and nothing is held.
- **AC-H6** Once the clock passes a hold's expiry, its seats can be held by another customer.
  No background job is needed.
- **AC-H7** Requests with an empty seat list, more than 10 seats, or duplicate seat ids are
  rejected (400) before any locking. Unknown seat ids, or seats not belonging to the showtime,
  are rejected (400 `INVALID_SEATS`) with nothing held.
- **AC-H8** A customer with an unexpired active hold for a showtime cannot create a second one
  (409 `ACTIVE_HOLD_EXISTS`). After that hold expires or is released, a new hold succeeds.
- **AC-H9** Holds cannot be created for a showtime that has started or is cancelled.
- **AC-H10** A customer can release their own hold. Attempts to view, release, or pay for
  another customer's hold return 404. Release is rejected while a payment is `PENDING` or `UNKNOWN`.

### Payment and booking
- **AC-B1** A successful payment on a valid hold creates exactly one confirmed booking, marks
  the seats `BOOKED`, sets the attempt to `SUCCEEDED`, and writes one outbox event, all in one
  transaction.
- **AC-B2** A declined payment creates no booking. The attempt is `DECLINED`, and the hold stays
  active.
- **AC-B3** After a decline, a new payment with a **new** idempotency key is accepted while the
  hold is valid. A new attempt is rejected once the hold has expired or is too close to expiry.
- **AC-B4** Repeating a request with the **same** idempotency key never invokes the simulated
  charge a second time (the provider's charge count stays at 1). It returns the original
  attempt's current result, including when the requests are concurrent.
- **AC-B5** **An expired hold is never converted into a booking**, even if its seats are still
  available. A successful capture that arrives after expiry results in a `VOIDED` attempt, no
  booking, and no `BOOKED` seats.
- **AC-B6** A payment timeout results in `UNKNOWN`, never `DECLINED` by default. Resolution
  produces `SUCCEEDED` (captured and hold valid), `VOIDED` (captured and hold invalid), or
  `DECLINED` only when the provider ledger reports `DECLINED` or `NOT_FOUND`.
- **AC-B7** While an attempt for a hold is `PENDING` or `UNKNOWN`, no other attempt can be
  started for that hold.
- **AC-B8** A seat can never be booked twice. This is enforced by
  `UNIQUE (showtime_id, seat_id)` on `booking_seat`; a test inserting a duplicate directly
  fails at the database.
- **AC-B9** The simulated provider refuses to charge or void while a database transaction is
  active.

### Authentication and authorization
- **AC-U1** Hold, payment, and booking endpoints return 401 without valid credentials.
- **AC-U2** A theater admin can create a showtime for an assigned theater, and the seat
  inventory is created. For any other theater the request returns 403.
- **AC-U3** Customers cannot call admin endpoints (403), and admins cannot create holds (403).
- **AC-U4** Overlapping showtimes on the same screen are rejected (409).

### End-to-end (Playwright, against Docker Compose)
- **AC-E1** A customer logs in, searches, selects seats, pays successfully, and sees a booking
  reference.
- **AC-E2** A customer whose payment is declined sees a decline message, no booking is created,
  and the seats remain held by them.

## 6. Failure cases

| # | Failure | Expected behavior |
|---|---|---|
| F1 | Two customers select the same seat simultaneously | One hold succeeds; the other gets 409 `SEATS_UNAVAILABLE` (or `SEATS_BUSY` if the lock wait is exceeded). |
| F2 | Overlapping multi-seat requests | Deterministic lock order: no deadlock, no partial holds. |
| F3 | Abandoned checkout | Hold expires at TTL (per the injected clock); seats become available. |
| F4 | Payment declined | `DECLINED`; no booking; hold remains; retry needs a new key and a still-valid hold. |
| F5 | Payment times out | `UNKNOWN`; not treated as declined; resolved by status query when the client polls; release and new attempts are blocked until it is resolved or the hold expires. |
| F6 | Capture succeeds after the hold expired (seats taken **or still free**) | No booking; capture voided; attempt `VOIDED`; customer told they were not charged. |
| F7 | Double-click or network retry of the same payment action | Same idempotency key: no second charge; original result returned. |
| F8 | App crashes after charge, before confirmation | Attempt stays `PENDING`; later resolution confirms (hold valid) or voids (hold expired). Relies on the simulated provider's ledger; see architecture §6.7 for real-provider limitations. |
| F9 | App crashes before the charge is sent | Resolution finds `NOT_FOUND` and records `DECLINED (NOT_RECEIVED)`. |
| F10 | Unknown attempt that nobody polls | Stays `UNKNOWN` (no background reconciliation in the MVP); hold expires; any later resolution voids. Documented limitation. |
| F11 | Invalid seat input (empty, >10, duplicates, unknown, wrong screen) | 400 with details; nothing held. |
| F12 | Showtime started or cancelled | Holds rejected; confirmation rejected and any capture voided. |
| F13 | Customer acts on another customer's hold or payment | 404; nothing changed. |
| F14 | Admin acts on an unassigned theater | 403; nothing changed. |

## 7. Assumptions and open questions

- **Decided (engineering owner):** expired holds are never converted; HTTP Basic with seeded
  users; login required to hold; one active hold per customer per showtime; UTC `Clock` for all
  expiry logic.
- **Assumed (proposed defaults):** hold TTL 8 minutes; at most 10 seats per hold; at most 3
  payment attempts per hold; prices fixed per seat category per showtime; single currency.
- **Decided in Task 06:** instants are stored and compared in UTC, but the search "date", the
  local times shown for a showtime, and an admin's entered start time are in the theater's city
  time zone (`city.time_zone`; all sample cities use `America/Chicago`).

## 8. Delivery status (final audit, 2026-11-02; updated for Tasks 10–12, 2026-11-03)

**Delivered and covered by automated tests:**
- Workflows 3.1–3.7 (3.6, "view my bookings", was added in Task 10; 3.7 gained the admin
  dashboard, screen management and schedule views in Task 11, and assigned-theater showtime
  cancellation in Task 12).
- Every acceptance criterion in §5.
- Failure cases F1–F7 and F9–F14.
- F8 (a crash between charge and confirmation) is handled by on-demand resolution of a stale
  `PENDING` attempt, and is tested by simulating that state rather than killing the process.
- F10 behaves as documented: nobody polls, so the attempt stays unresolved.

**Deferred (not implemented):**

| Feature | Why / where recorded |
|---|---|
| Background workers: hold-cleanup sweeper, outbox relay, scheduled payment reconciliation | Cut list item 2; correctness relies on lazy expiry and on-demand resolution instead |
| Redis, Kafka, Solr | ADRs 0001–0003 |
| Load testing to the 500,000-visitor target | Non-goal; design notes only (`docs/architecture.md` §12) |
