# Product Scope

> **Status: PROPOSED. Nothing in this document is implemented.**
> Every feature, workflow, and acceptance criterion below is a planned target for the
> 10-hour assessment build. Implementation status will be tracked in
> `docs/implementation-plan.md` as work progresses.

## 1. Product summary

A web application where customers find movie showtimes across many theaters and cities,
select specific seats, pay (simulated), and receive a confirmed booking. Theater administrators
manage their theaters, screens, and showtimes.

Design target (not a performance claim): 2,000 theaters across 300 cities, with traffic spikes
of up to 500,000 visitors in 10 minutes during major releases. See `docs/architecture.md`
for how the design accounts for this and what is out of reach for this assessment.

## 2. Roles

| Role | Description | Proposed MVP authentication |
|---|---|---|
| **Customer** | Searches showtimes, holds seats, pays, views own bookings. | Seeded demo user accounts (simplified login). |
| **Theater Admin** | Manages screens, seat layouts, and showtimes for the theater(s) they are assigned to. | Seeded demo admin accounts scoped to specific theaters. |
| Anonymous visitor | Can search and view showtimes/seat maps; must log in to hold seats. | — |

The movie catalog (movies, languages, genres) is **seeded via migrations** in the MVP; there is
no platform-admin role. (See "Unresolved" in §7.)

## 3. MVP workflows (proposed)

### 3.1 Customer: search showtimes
1. Customer selects a city and date (defaults to today).
2. Customer optionally filters by language and genre, and/or types a movie or theater name.
3. Search tolerates typos (e.g. "Intersteller" finds "Interstellar"; "Regall" finds "Regal").
4. Results list showtimes grouped by movie and theater, ordered by relevance then start time.

### 3.2 Customer: view seat map
1. Customer opens a showtime.
2. The seat map shows each seat as **available**, **held**, or **booked**, plus seat category and price.
   (Held-by-others and booked are both shown as unavailable to the customer.)

### 3.3 Customer: hold seats
1. Customer selects 1–10 seats and clicks "Continue".
2. The system atomically places a temporary hold on **all** selected seats or **none** of them.
3. On success the customer sees a checkout page with a countdown (proposed hold TTL: 8 minutes).

### 3.4 Customer: pay and confirm
1. Customer chooses a simulated payment outcome (success / decline / timeout) via test card
   numbers or a scenario selector on the checkout page.
2. **Success** → booking is confirmed, seats become permanently booked, a confirmation page
   shows a booking reference, and a "booking confirmed" notification event is recorded.
3. **Decline** → no booking; the customer may retry payment while the hold is still valid.
4. **Timeout** → the system does not confirm the booking on an unknown outcome; it resolves
   the payment status before deciding (see architecture §5.4). Customer sees a clear message.

### 3.5 Customer: abandonment
1. Customer leaves checkout or lets the countdown expire.
2. The hold expires and the seats become available to others without any manual action.
3. Customer may also explicitly release the hold ("Cancel").

### 3.6 Customer: view my bookings
1. Customer sees a list of their confirmed bookings (movie, theater, time, seats, reference).

### 3.7 Theater Admin: manage showtimes
1. Admin sees only the theaters they are assigned to.
2. Admin creates a showtime: movie, screen, start time, language, base price.
   Creating a showtime generates the seat inventory for that show from the screen layout.
3. Admin cannot create overlapping showtimes on the same screen.
4. Admin may cancel a showtime **only if it has no confirmed bookings** (MVP simplification).

### 3.8 Theater Admin: manage screens and layouts (stretch)
Screens and seat layouts are seeded in the MVP. An admin UI for editing them is a stretch goal
and the first candidate to cut (see `docs/implementation-plan.md`).

## 4. Explicit non-goals

The following are deliberately **out of scope** for this assessment:

- Real payment providers, real card data, PCI compliance, real refunds.
- Real identity management (OAuth/SSO, sign-up, password reset, MFA).
- Real email/SMS/push delivery (notifications are recorded and logged only).
- Booking cancellation and refunds by customers after confirmation.
- Dynamic pricing, coupons, loyalty programs, food & beverage add-ons, gift cards.
- Multi-currency, taxes, localization/i18n of the UI.
- Platform-admin role and movie catalog management UI.
- Recommendations, reviews, ratings, trailers.
- Virtual waiting room / queueing, CDN, autoscaling, multi-region deployment
  (discussed as design considerations only).
- Load testing to the 500,000-visitor target.
- Accessibility audit and mobile apps (the UI should be usable, not audited).

## 5. Acceptance criteria (proposed)

Each criterion should map to at least one automated test once implemented.

### Search
- **AC-S1** Searching by city and date returns only showtimes in that city on that date.
- **AC-S2** Language and genre filters narrow results correctly and can be combined.
- **AC-S3** A movie or theater name with a single-character typo still returns the intended match.
- **AC-S4** A search with no matches returns an empty result (HTTP 200), not an error.
- **AC-S5** Search results are paginated; page size is capped.

### Seat holds
- **AC-H1** Holding available seats succeeds and returns a hold id and expiry time.
- **AC-H2** A hold is all-or-nothing: if any requested seat is unavailable, no seat is held
  and the response identifies the conflicting seats (HTTP 409).
- **AC-H3** When N concurrent requests compete for the same seat, exactly one succeeds.
- **AC-H4** An expired hold no longer blocks the seat; another customer can hold it.
- **AC-H5** A customer cannot hold more than 10 seats in one hold, or hold seats for a showtime
  that has started or been cancelled.
- **AC-H6** A customer can release their own hold; they cannot release another customer's hold.

### Payment and booking
- **AC-B1** Successful payment on a valid hold creates exactly one confirmed booking and marks
  the seats booked.
- **AC-B2** Declined payment creates no booking; seats remain held until hold expiry or release.
- **AC-B3** Payment timeout never results in a confirmed booking without a confirmed payment.
- **AC-B4** Repeating the same confirm request (same idempotency key) does not create a second
  booking or a second charge.
- **AC-B5** A booked seat can never be held or booked again — enforced by a database
  constraint in addition to application logic.
- **AC-B6** A confirmed booking records exactly one "booking confirmed" outbox event in the
  same transaction as the booking.

### Admin
- **AC-A1** An admin can create a showtime for a screen in their theater; seat inventory is created.
- **AC-A2** An admin cannot create or modify showtimes for theaters they are not assigned to (HTTP 403).
- **AC-A3** Overlapping showtimes on the same screen are rejected (HTTP 409).

### End-to-end (Playwright)
- **AC-E1** A customer can search, select seats, pay successfully, and see a booking reference.
- **AC-E2** A customer whose payment is declined sees a failure message and no booking is created.

## 6. Failure cases to handle

| # | Failure | Expected behavior |
|---|---|---|
| F1 | Two customers select the same seat simultaneously | One hold succeeds; the other gets 409 with the conflicting seats. |
| F2 | Customer holds seats and abandons checkout | Hold expires after TTL; seats become available. |
| F3 | Payment declined | No booking; hold remains until expiry so the customer can retry. |
| F4 | Payment times out | Outcome treated as unknown; status is resolved before confirming or failing. No double charge, no booking without payment. |
| F5 | Payment succeeds but hold expired and seat was taken meanwhile | Booking fails; payment is (simulated) refunded/voided; customer sees an explanatory error. |
| F6 | Client retries a confirm request (double click, network retry) | Idempotent: same result, no second booking or charge. |
| F7 | Showtime already started or cancelled | Holds and confirmations rejected. |
| F8 | Application crash between payment and confirmation | Payment attempt is persisted before calling the provider; it can be reconciled. Hold expiry still frees seats. |
| F9 | Notification delivery fails | Booking stays confirmed; event remains in outbox and is retried (at-least-once). |
| F10 | Invalid input (unknown seat, seat from another screen, >10 seats) | 400/404 with a clear error; no partial state. |

## 7. Assumptions and unresolved scope questions

- **Assumed:** Movie catalog is seeded; no platform admin. *(Confirm with reviewer.)*
- **Assumed:** Authentication is simplified (seeded users); exact mechanism is unresolved
  (e.g. HTTP Basic vs. simple session login).
- **Assumed:** Hold TTL of 8 minutes and a 10-seat maximum per hold — tunable configuration.
- **Assumed:** Prices are fixed per seat category per showtime; single currency.
- **Unresolved:** Whether anonymous users may hold seats (current proposal: no, login required).
- **Unresolved:** Whether a customer may have more than one active hold per showtime
  (current proposal: one active hold per customer per showtime).
