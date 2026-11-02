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
| `GET /api/showtimes` | anyone | 200 page | 400 `INVALID_REQUEST` |
| `GET /api/showtimes/{id}` | anyone | 200 detail | 404 |
| `GET /api/showtimes/{id}/seats` | anyone | 200 seat map | 404 |
| `POST /api/showtimes/{id}/holds` | customer | 201 hold | 400 `INVALID_REQUEST` / `INVALID_SEATS`; 404; 409 `SEATS_UNAVAILABLE` / `SEATS_BUSY` / `ACTIVE_HOLD_EXISTS` / `SHOWTIME_CLOSED` |
| `DELETE /api/holds/{holdId}` | customer (owner) | 204 | 404 (not yours or unknown); 409 `PAYMENT_IN_PROGRESS` / `HOLD_CONVERTED` |
| `POST /api/holds/{holdId}/payment` | customer (owner) | 201 booked; 202 `UNKNOWN` | 400; 402 `PAYMENT_DECLINED`; 404; 409 `PAYMENT_VOIDED` / `PAYMENT_IN_PROGRESS` / `HOLD_EXPIRED` / `HOLD_NOT_ACTIVE` / `HOLD_TOO_CLOSE_TO_EXPIRY` / `TOO_MANY_ATTEMPTS` / `SEATS_BUSY`; 422 `IDEMPOTENCY_KEY_REUSED` |
| `GET /api/payments/{paymentId}` | customer (owner) | 200 payment | 404 |
| `GET /api/bookings/{bookingId}` | customer (owner) | 200 booking | 404 |
| `POST /api/admin/showtimes` | theater admin, assigned theaters only | 201 created showtime | 400 `INVALID_REQUEST` / `INVALID_START_TIME`; 403; 409 `SHOWTIME_OVERLAP` / `SCREEN_BUSY` |

Rules for every protected endpoint:
- No credentials or wrong credentials: 401 `UNAUTHENTICATED`.
- Wrong role: 403 `FORBIDDEN`. For example, a customer calling `/api/admin/**`, or a theater admin
  creating a hold.
- Another customer's hold, payment or booking: 404 `NOT_FOUND`, so their IDs are not revealed.

**Designed but not implemented:**
- `GET /api/holds/{id}`
- `GET /api/bookings` (a list of my bookings)
- Showtime cancellation

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
