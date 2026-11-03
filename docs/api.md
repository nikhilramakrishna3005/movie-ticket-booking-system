# REST API Reference

> **Status: IMPLEMENTED.** Every endpoint below exists in the backend controllers
> (`backend/src/main/java/com/moviebooking/*/…Controller.java`). The examples are real responses
> captured from the local Docker Compose stack on 2026-11-02, shortened where marked with `…`.
> IDs, references and timestamps differ on every run.

- **Base URL:** `http://localhost:3000/api`, served through the frontend's nginx proxy. The backend
  container's port is not published.
- **Format:** JSON request and response bodies. Instants are UTC (`…Z`). `localDate`,
  `localStartTime` and `startsAtLocal` are in the theater's city time zone (`timeZone`).
- **Authentication:** HTTP Basic with the public demo accounts in the README. There are no sessions,
  cookies or tokens. A request with the header `X-Requested-With: XMLHttpRequest` gets a 401
  without a `WWW-Authenticate` challenge, so browsers don't show their native login dialog.

## Endpoints

| Method and path | Who | Success | Main errors |
|---|---|---|---|
| `GET /actuator/health` | anyone | 200 `{"status":"UP"}` | — |
| `GET /api/auth/me` | any signed-in user | 200 profile | 401 |
| `GET /api/cities` | anyone | 200 list | — |
| `GET /api/movies` | anyone | 200 list | — |
| `GET /api/languages` | anyone | 200 list | — |
| `GET /api/showtimes` | anyone | 200 page | 400 `INVALID_REQUEST` |
| `GET /api/showtimes/{id}` | anyone | 200 detail | 404 |
| `GET /api/showtimes/{id}/seats` | anyone | 200 seat map | 404 |
| `POST /api/showtimes/{id}/holds` | customer | 201 hold | 400 `INVALID_REQUEST` / `INVALID_SEATS`; 404; 409 `SEATS_UNAVAILABLE` / `SEATS_BUSY` / `ACTIVE_HOLD_EXISTS` / `SHOWTIME_CLOSED` |
| `GET /api/holds/{holdId}` | customer (owner) | 200 hold | 404 (not yours or unknown) |
| `DELETE /api/holds/{holdId}` | customer (owner) | 204 | 404 (not yours or unknown); 409 `PAYMENT_IN_PROGRESS` / `HOLD_CONVERTED` |
| `POST /api/holds/{holdId}/payment` | customer (owner) | 201 booked; 202 `UNKNOWN` | 400; 402 `PAYMENT_DECLINED`; 404; 409 `PAYMENT_VOIDED` / `PAYMENT_IN_PROGRESS` / `HOLD_EXPIRED` / `HOLD_NOT_ACTIVE` / `HOLD_TOO_CLOSE_TO_EXPIRY` / `TOO_MANY_ATTEMPTS` / `SEATS_BUSY`; 422 `IDEMPOTENCY_KEY_REUSED` |
| `GET /api/payments/{paymentId}` | customer (owner) | 200 payment | 404 |
| `GET /api/bookings` | customer (own bookings only) | 200 page | 400 `INVALID_REQUEST` |
| `GET /api/notifications` | customer (own inbox only) | 200 page | 400 `INVALID_REQUEST` |
| `GET /api/bookings/{bookingId}` | customer (owner) | 200 booking | 404 |
| `POST /api/admin/showtimes` | theater admin, assigned theaters only | 201 created showtime | 400 `INVALID_REQUEST` / `INVALID_START_TIME`; 403; 409 `SHOWTIME_OVERLAP` / `SCREEN_BUSY` |
| `POST /api/admin/showtimes/{id}/cancel` | theater admin, assigned | 200 cancelled | 403; 404; 409 `SHOWTIME_HAS_BOOKINGS` / `SHOWTIME_HAS_UNRESOLVED_PAYMENTS` / `SHOWTIME_HAS_ACTIVE_HOLDS` |
| `GET /api/admin/theaters` | theater admin | 200 assigned theaters | 401; 403 |
| `GET /api/admin/theaters/{id}/showtimes` | theater admin, assigned | 200 day's schedule | 400 `INVALID_REQUEST`; 403 |
| `POST /api/admin/theaters/{id}/screens` | theater admin, assigned | 201 screen | 400 `INVALID_REQUEST`; 403; 409 `SCREEN_NAME_TAKEN` |
| `GET /api/admin/screens/{id}` | theater admin, assigned | 200 seating plan | 403; 404 |
| `PUT /api/admin/screens/{id}/layout` | theater admin, assigned | 200 seating plan | 400; 403; 404; 409 `SCREEN_HAS_SHOWTIMES` / `SCREEN_BUSY` |

Rules for every protected endpoint:
- No credentials or wrong credentials: 401 `UNAUTHENTICATED`.
- Wrong role: 403 `FORBIDDEN`. For example, a customer calling `/api/admin/**`, or a theater admin
  creating a hold.
- Another customer's hold, payment or booking: 404 `NOT_FOUND`, so their IDs are not revealed.

**Designed but not implemented** (full list in [`docs/deferred-scope.md`](deferred-scope.md)):
- Hold sweeper and scheduled payment reconciliation
- Customer booking cancellation / refunds
- Kafka (the local outbox poller is not a broker; ADR 0002)

## Errors

Every error uses one JSON shape. `details` is `null` or an object.

```json
{"code":"SEATS_UNAVAILABLE","message":"Some seats are already held or booked","details":{"seatIds":[261,262]}}
```

Generic codes you may also see:
- `INTERNAL_ERROR`
- `METHOD_NOT_ALLOWED`
- `UNSUPPORTED_MEDIA_TYPE`
- `NOT_ACCEPTABLE`

## Browsing (anonymous)

### `GET /api/cities`

```json
[{"id":3,"name":"Austin","stateCode":"TX","timeZone":"America/Chicago"},
 {"id":1,"name":"Dallas","stateCode":"TX","timeZone":"America/Chicago"},
 {"id":2,"name":"Houston","stateCode":"TX","timeZone":"America/Chicago"}]
```

### `GET /api/movies`

Sorted by title.

```json
[{"id":7,"title":"Coco","durationMinutes":105,"certification":"PG","releaseDate":"2017-11-22",
  "originalLanguage":{"code":"en","name":"English"},"genres":["Animation","Family","Fantasy"]}, …]
```

### `GET /api/languages`

Every language a showtime may be shown in, sorted by name. Added in Task 11 so the admin form
does not have to harvest codes from movies.

```json
[{"code":"en","name":"English"},{"code":"hi","name":"Hindi"},{"code":"ja","name":"Japanese"},
 {"code":"ko","name":"Korean"},{"code":"es","name":"Spanish"}]
```

### `GET /api/showtimes`

Query parameters (all optional):

| Parameter | Meaning |
|---|---|
| `cityId` | Filter by city. |
| `date` | Local date `yyyy-MM-dd` in the theater's zone. Default: today in each city's zone. |
| `q` | Typo-tolerant text matched against movie titles and theater names (`pg_trgm`). |
| `language` | Language code, e.g. `en`. |
| `genre` | Genre name, case-insensitive. |
| `movieId` | Filter by movie. |
| `page` | Page number, 0–1000. Default 0. |
| `size` | Page size. Default 20, capped at 50. |

The search returns only `SCHEDULED` showtimes that have not started. **Unknown query parameters
are ignored.** For example, `city=1` is silently ignored; the parameter is `cityId`.

```
GET /api/showtimes?cityId=2&date=2026-11-03&q=Intersteller
```
```json
{"items":[{"showtimeId":1,"movieId":1,"movieTitle":"Interstellar","certification":"PG-13","durationMinutes":169,
  "language":{"code":"en","name":"English"},"theaterId":3,"theaterName":"Galleria Screens","cityId":2,
  "cityName":"Houston","stateCode":"TX","screenId":5,"screenName":"Screen 1",
  "startsAt":"2026-11-03T22:30:00Z","endsAt":"2026-11-04T01:34:00Z","localDate":"2026-11-03",
  "localStartTime":"16:30:00","timeZone":"America/Chicago"}],
 "page":0,"size":20,"totalItems":1,"totalPages":1}
```

An invalid parameter returns 400. For example, `size=0` returns:

```json
{"code":"INVALID_REQUEST","message":"size must be at least 1","details":{"parameter":"size"}}
```

### `GET /api/showtimes/{id}`

```json
{"showtimeId":2,"status":"SCHEDULED","startsAt":"2026-11-03T19:00:00Z","endsAt":"2026-11-03T21:43:00Z",
 "localDate":"2026-11-03","localStartTime":"13:00:00","localEndTime":"15:43:00","timeZone":"America/Chicago",
 "movie":{"id":2,"title":"Inception","durationMinutes":148,"certification":"PG-13","releaseDate":"2010-07-16",
          "originalLanguage":{"code":"en","name":"English"},"genres":["Action","Sci-Fi","Thriller"]},
 "language":{"code":"en","name":"English"},
 "theater":{"id":2,"name":"Starlight Cinemas Downtown","address":"1200 Elm St, Dallas, TX",
            "city":{"id":1,"name":"Dallas","stateCode":"TX"}},
 "screen":{"id":1,"name":"Screen 1"},
 "prices":[{"category":"STANDARD","price":12.50,"seatCount":40},{"category":"PREMIUM","price":16.00,"seatCount":20}]}
```

Started and cancelled showtimes still return their details, but the search no longer lists them.

### `GET /api/showtimes/{id}/seats`

The `status` field is the seat's effective status. A hold whose expiry has passed shows as
`AVAILABLE`, even before any cleanup runs.

```json
{"showtimeId":2,"showtimeStatus":"SCHEDULED","startsAt":"2026-11-03T19:00:00Z",
 "seats":[{"seatId":201,"rowLabel":"A","seatNumber":1,"category":"STANDARD","price":12.50,"status":"AVAILABLE"}, …]}
```

## Account

### `GET /api/auth/me`

```bash
curl -u alice:alice-demo-pass http://localhost:3000/api/auth/me
```
```json
{"id":1,"username":"alice","displayName":"Alice (demo customer)","role":"CUSTOMER"}
```

## Holds (customer)

### `POST /api/showtimes/{id}/holds`

The request body lists 1–10 distinct seat ids. The hold is all-or-nothing and lasts 8 minutes,
computed from the server's UTC `Clock`.

```bash
curl -u alice:alice-demo-pass -H 'Content-Type: application/json' \
  -d '{"seatIds":[261,262]}' http://localhost:3000/api/showtimes/1081/holds
```
```json
{"holdId":"db9e0b24-fb97-4dee-8bce-90eec12c20e2","showtimeId":1081,"seatIds":[261,262],
 "status":"ACTIVE","expiresAt":"2026-11-02T21:52:48.129131Z"}
```

| Situation | Response |
|---|---|
| Another customer already holds a seat | 409 `{"code":"SEATS_UNAVAILABLE",…,"details":{"seatIds":[261,262]}}`. Nothing is held. |
| Empty seat list | 400 `{"code":"INVALID_REQUEST","message":"Select between 1 and 10 seats","details":null}` |
| More than 10 seats or duplicate ids | 400 `INVALID_REQUEST` |
| Unknown seats, or seats of another screen | 400 `INVALID_SEATS` |
| The customer already has an unexpired hold for this showtime | 409 `ACTIVE_HOLD_EXISTS`, with `details.holdId` |
| The showtime has started or is cancelled | 409 `SHOWTIME_CLOSED` |
| A seat lock is not acquired within 2 s | 409 `SEATS_BUSY` (retryable). Nothing is held. |

### `GET /api/holds/{holdId}`

Owner only. Another customer's hold, or an unknown id, is 404. The stored expiry is not
extended. An ACTIVE row whose `expiresAt` is not after the injected Clock is returned as
`EXPIRED` without writing the row. Converted holds include the booked seat ids; released holds
that no longer own inventory return an empty `seatIds` list. The body is the same shape as
create-hold and does not include payment fields.

```json
{"holdId":"db9e0b24-fb97-4dee-8bce-90eec12c20e2","showtimeId":1081,"seatIds":[261,262],
 "status":"ACTIVE","expiresAt":"2026-11-02T21:52:48.129131Z"}
```

Reload recovery in the React checkout uses this endpoint after the customer signs in again.
Credentials stay in memory only, so a reload always asks for the password; this GET does not
change that.

### `DELETE /api/holds/{holdId}`

- Releases the hold: 204. Releasing an already expired or released hold is also 204.
- Another customer's hold: 404.
- A payment is `PENDING` or `UNKNOWN`: 409 `PAYMENT_IN_PROGRESS`.
- The hold was already booked: 409 `HOLD_CONVERTED`.

## Payments and bookings (customer)

### `POST /api/holds/{holdId}/payment`

- **Header:** `Idempotency-Key`, 1–100 characters. Send one key per payment attempt, and resend
  the same key when retrying that attempt.
- **Body:** `{"scenario":"SUCCESS" | "DECLINE" | "TIMEOUT"}`. It selects the outcome of the
  in-process simulated provider; no real payment is made.
- **Preconditions:** the hold must be active, have at least 60 s left, and have fewer than 3
  attempts.

**`DECLINE`: 402.** The hold stays active, and a retry needs a **new** key.

```json
{"code":"PAYMENT_DECLINED","message":"The payment was declined; retry with a new Idempotency-Key",
 "details":{"paymentId":"2b74bc5c-0383-4a9c-b829-1476f31a238d","status":"DECLINED"}}
```

**`SUCCESS`: 201.** The response includes the confirmed booking.

```json
{"paymentId":"30355c1d-0b5b-4bfb-9124-30c774ed4e98","holdId":"db9e0b24-fb97-4dee-8bce-90eec12c20e2",
 "status":"SUCCEEDED","amount":25.00,
 "booking":{"bookingId":"f4b71a29-33a8-42a9-a99b-213b5d0f29e4","reference":"AVA8UJ72","status":"CONFIRMED",
   "paymentId":"30355c1d-0b5b-4bfb-9124-30c774ed4e98","showtimeId":1081,"movieTitle":"Inception",
   "theaterName":"Starlight Cinemas Downtown","screenName":"Screen 2","city":"Dallas, TX",
   "startsAt":"2027-02-06T01:00:00Z",
   "seats":[{"seatId":261,"rowLabel":"A","seatNumber":1,"category":"STANDARD","price":12.50},
            {"seatId":262,"rowLabel":"A","seatNumber":2,"category":"STANDARD","price":12.50}],
   "totalAmount":25.00,"confirmedAt":"2026-11-02T21:44:48.657615Z"}}
```

Replaying the same key returns the same payment and booking without charging again.

**`TIMEOUT`: 202.** The outcome is unknown, never "declined". Poll `GET /api/payments/{id}` for
the result. While the attempt is unknown, the hold cannot be released (409
`PAYMENT_IN_PROGRESS`) and no other attempt can start.

```json
{"paymentId":"2115311c-9542-4747-b39d-3356f642e534","holdId":"e5ee3cdd-3dc2-400f-8cc8-093440c173d2",
 "status":"UNKNOWN","amount":12.50,"booking":null}
```

Other outcomes:
- A capture after the hold could no longer be booked, e.g. it expired: 409 `PAYMENT_VOIDED`.
  The charge is voided and nothing is booked.
- The same key sent for a different hold: 422 `IDEMPOTENCY_KEY_REUSED`.

### `GET /api/payments/{paymentId}`

This returns the attempt's current state. For an `UNKNOWN` or stale `PENDING` attempt, it first
asks the simulated provider and resolves the attempt to `SUCCEEDED`, `VOIDED` or `DECLINED`.
Polling the `TIMEOUT` payment above returned:

```json
{"paymentId":"2115311c-9542-4747-b39d-3356f642e534","status":"SUCCEEDED","amount":12.50,
 "booking":{"reference":"XRN54PHW","status":"CONFIRMED", …}}
```

### `GET /api/bookings`

This lists the signed-in customer's own `CONFIRMED` bookings, newest first (by confirmation
time, then booking id). The customer always comes from the credentials. **There is no customer
parameter**; a `customerId=` in the query is ignored like any other unknown parameter. Holds
that were never paid, and declined attempts, are not listed. A theater admin gets 403.

| Parameter | Meaning |
|---|---|
| `page` | Page number, 0–1000. Default 0. |
| `size` | Page size. Default 10, capped at 50. |

```bash
curl -u alice:alice-demo-pass 'http://localhost:3000/api/bookings?size=2'
```
```json
{"items":[{"bookingId":"751764aa-85a3-4f99-b50d-d949f0d24b76","reference":"9956RB4M","status":"CONFIRMED",
  "showtimeId":26,"movieTitle":"Interstellar","theaterName":"Congress Avenue Cinema","screenName":"Screen 1",
  "city":"Austin, TX","startsAt":"2026-11-05T19:00:00Z","localDate":"2026-11-05","localStartTime":"13:00:00",
  "timeZone":"America/Chicago",
  "seats":[{"seatId":97,"rowLabel":"D","seatNumber":7,"category":"STANDARD","price":12.50}],
  "totalAmount":12.50,"confirmedAt":"2026-11-03T17:27:54.383776Z"}],
 "page":0,"size":2,"totalItems":1,"totalPages":1}
```

A customer with no bookings gets `{"items":[],"page":0,"size":10,"totalItems":0,"totalPages":0}`.
A page past the end also returns an empty `items` list. An invalid parameter returns 400. For
example, `page=1001` returns:

```json
{"code":"INVALID_REQUEST","message":"page must be between 0 and 1000","details":{"parameter":"page"}}
```

### `GET /api/notifications`

The signed-in customer's local inbox from the outbox relay, newest first. The customer comes
from the credentials. A theater admin gets 403. Bob does not see Alice's rows; he gets an empty
page, not her event ids. This is not email.

Paging matches `GET /api/bookings` (`page` 0–1000, `size` default 10, capped at 50).

```json
{"items":[{"id":"…","eventId":"…","bookingId":"…","eventType":"BookingConfirmed","reference":"9956RB4M","createdAt":"2026-11-03T17:27:54.383776Z"}],
 "page":0,"size":10,"totalItems":1,"totalPages":1}
```

### `GET /api/bookings/{bookingId}`

This returns the same booking object as in the `SUCCESS` response. Another customer gets 404.

## Theater admin

### `POST /api/admin/showtimes`

The admin must be assigned to the theater that owns `screenId`.

```bash
curl -u admin.dallas:dallas-admin-demo -H 'Content-Type: application/json' -d '{
  "movieId":2,"screenId":2,"languageCode":"en","startsAtLocal":"2027-02-05T19:00:00",
  "standardPrice":12.50,"premiumPrice":16.00}' http://localhost:3000/api/admin/showtimes
```
```json
{"showtimeId":1081,"theaterId":2,"screenId":2,"movieId":2,"languageCode":"en","status":"SCHEDULED",
 "startsAt":"2027-02-06T01:00:00Z","endsAt":"2027-02-06T03:43:00Z","localDate":"2027-02-05",
 "localStartTime":"19:00:00","timeZone":"America/Chicago","seatCount":40}
```

`endsAt` is the movie's duration plus 15 minutes of cleaning time. The seat inventory
(`show_seat` rows) is created in the same transaction.

| Situation | Response |
|---|---|
| Overlaps a scheduled showtime on the same screen | 409 `{"code":"SHOWTIME_OVERLAP",…,"details":{"screenId":2,"startsAt":"…","endsAt":"…"}}` |
| Screen of a theater the admin is not assigned to (e.g. `admin.houston` on Starlight) | 403 `FORBIDDEN` |
| Missing or invalid fields | 400 `{"code":"INVALID_REQUEST","message":"Missing or invalid fields","details":{"fields":["languageCode","startsAtLocal","standardPrice","premiumPrice"]}}` |
| Start time in the past, or a local time that doesn't exist or is ambiguous on a daylight-saving change | 400 `INVALID_START_TIME` |
| Another showtime is being scheduled on the same screen and the lock wait is exceeded | 409 `SCREEN_BUSY` |

Field rules:
- `languageCode` is two lower-case letters.
- `startsAtLocal` has whole minutes.
- Prices are positive, have at most 2 decimals, and are at most 1000.

### `POST /api/admin/showtimes/{id}/cancel`

Marks the showtime `CANCELLED`. Seat inventory, holds and bookings are not deleted. Repeating
the call on an already cancelled showtime is 200 with the same body. Public search omits
cancelled shows; the admin schedule still lists them.

Cancellation is refused, with nothing changed, when:

| Situation | Response |
|---|---|
| Confirmed bookings (BOOKED seats) | 409 `SHOWTIME_HAS_BOOKINGS` (`details.bookedSeats`) |
| A payment attempt is `PENDING` or `UNKNOWN` | 409 `SHOWTIME_HAS_UNRESOLVED_PAYMENTS` (`details.inFlightPayments`) |
| An unexpired ACTIVE hold | 409 `SHOWTIME_HAS_ACTIVE_HOLDS` (`details.activeHolds`) |
| Theater the admin is not assigned to | 403 `FORBIDDEN` |
| Unknown showtime id | 404 `NOT_FOUND` |

Expired ACTIVE holds are marked `EXPIRED` and their seats released in the cancelling
transaction. Live holds are not invalidated. An in-flight simulated capture is never voided by
this endpoint: the admin must wait until the attempt settles. If it then books, cancellation
stays blocked.

```json
{"showtimeId":1081,"theaterId":2,"screenId":1,"status":"CANCELLED"}
```

### `GET /api/admin/theaters`

Only the theaters assigned to the caller. Screen counts come from the physical `seat` rows.
`layoutLocked` is true once the screen has ever had a showtime (including cancelled or past
ones), because that showtime's inventory and tickets reference those seats.

```json
[{"theaterId":1,"name":"Starlight Cinemas Downtown","address":"1200 Elm St, Dallas, TX",
  "city":{"id":1,"name":"Dallas","stateCode":"TX","timeZone":"America/Chicago"},
  "screens":[{"screenId":1,"name":"Screen 1","rowCount":6,"seatCount":60,"standardSeats":40,
              "premiumSeats":20,"layoutLocked":true},
             {"screenId":2,"name":"Screen 2","rowCount":5,"seatCount":40,"standardSeats":24,
              "premiumSeats":16,"layoutLocked":true}]}]
```

`admin.houston` sees Bayou Picture House, not Starlight. A customer gets 403.

### `GET /api/admin/theaters/{theaterId}/showtimes`

`date` is a local calendar date (`yyyy-MM-dd`) in the theater's zone. Omit it for today there.
The year must be 2000–2100. The list includes started and cancelled showtimes. A price is
`null` when the screen has no seats of that category. `heldSeats` counts only holds that have
not yet expired at the injected clock.

```
GET /api/admin/theaters/1/showtimes?date=2026-11-03
```
```json
{"theaterId":1,"date":"2026-11-03","timeZone":"America/Chicago","showtimes":[
  {"showtimeId":1,"screenId":1,"screenName":"Screen 1","movieId":1,"movieTitle":"Interstellar",
   "languageCode":"en","status":"SCHEDULED","startsAt":"2026-11-03T19:00:00Z",
   "endsAt":"2026-11-03T22:04:00Z","localStartTime":"13:00:00","localEndTime":"16:04:00",
   "seatCount":60,"bookedSeats":0,"heldSeats":0,"standardPrice":12.50,"premiumPrice":16.00}, …]}
```

### `POST /api/admin/theaters/{theaterId}/screens`

Rows are front-first. Labels are a single `A`–`Z` letter and unique. `seatCount` is 1–40.
`category` is `STANDARD` or `PREMIUM`. The name is trimmed and unique per theater.

```json
{"name":"Screen 3","rows":[{"rowLabel":"A","seatCount":8,"category":"STANDARD"},
                           {"rowLabel":"B","seatCount":8,"category":"PREMIUM"}]}
```

| Situation | Response |
|---|---|
| Duplicate name in the same theater | 409 `SCREEN_NAME_TAKEN` |
| Missing or invalid fields | 400 `INVALID_REQUEST` with `details.fields` |
| Theater the admin is not assigned to | 403 `FORBIDDEN` |

### `GET /api/admin/screens/{screenId}` and `PUT /api/admin/screens/{screenId}/layout`

The GET lists every seat in row then number order. The PUT replaces the seats **only while the
screen has never had a showtime**. It takes the same `screen` row lock as showtime creation, so
a layout change cannot interleave with the first `show_seat` insert. The composite FK
`show_seat (seat_id, screen_id) → seat` is the database backstop: a referenced seat cannot be
deleted.

| Situation | Response |
|---|---|
| Any showtime already exists for the screen | 409 `SCREEN_HAS_SHOWTIMES` |
| Another scheduling change holds the screen lock past `lock_timeout` | 409 `SCREEN_BUSY` |
| Unknown screen | 404 |
| Screen of a theater the admin is not assigned to | 403 |
