# movie-ticket-booking-system
Concurrent movie ticket booking platform built with Spring Boot, PostgreSQL, and React.

## Run locally

Prerequisites: Docker with the Compose plugin. Nothing else is needed to run the stack.

```bash
cp .env.example .env    # then set POSTGRES_PASSWORD to any local value
docker compose up --build
```

Open <http://localhost:3000>. Compose starts `postgres`, then `backend` once the database is
healthy, then `frontend` once `GET /actuator/health` on the backend reports `UP`. The frontend
container (nginx) serves the React app and proxies `/api` and `/actuator` to the backend, so the
browser only talks to one origin and no CORS is configured. The backend port is not published to
the host. Clean start (drops the database volume):

```bash
docker compose down -v && docker compose up --build
```

## Demo accounts (local only)

> **Local assessment use only.** These accounts and passwords are fictional and intentionally
> public. They are seeded by `backend/src/main/resources/db/migration/V8__demo_users.sql` and
> must never be used in a real deployment.

| Username | Password | Role | Assigned theater |
|---|---|---|---|
| `alice` | `alice-demo-pass` | Customer | — |
| `bob` | `bob-demo-pass` | Customer | — |
| `admin.dallas` | `dallas-admin-demo` | Theater admin | Starlight Cinemas Downtown |
| `admin.houston` | `houston-admin-demo` | Theater admin | Bayou Picture House |

The API uses stateless HTTP Basic authentication; there is no signup, session, or token. Basic
sends credentials with every request, so a real deployment would require HTTPS. Example, through
the frontend's proxy:

```bash
curl -u alice:alice-demo-pass http://localhost:3000/api/auth/me
```

`/actuator/health` and the browsing endpoints are public: `GET /api/cities`, `GET /api/movies`,
`GET /api/showtimes`, `GET /api/showtimes/{id}` and `GET /api/showtimes/{id}/seats`. Holds,
payments and bookings require a customer login. `/api/admin/**` requires the theater-admin role,
and an admin may only schedule showtimes for theaters assigned to them. Everything else under
`/api/**` requires authentication.

```bash
curl 'http://localhost:3000/api/showtimes?cityId=1&date=2026-11-03&q=Intersteller'
```

Search dates and the `localDate`/`localStartTime` fields are in the theater's city time zone
(all sample cities: `America/Chicago`); `startsAt`/`endsAt` are UTC instants. Sample showtimes
run from 2026-11-03 to 2027-01-31 (see `docs/sample-data.md`).

## Cinéva web app (customer flow)

The React app in `frontend/` (React 19, TypeScript, Vite, Tailwind CSS, Motion, Lucide) covers
the customer flow only; there is no admin UI.

1. **Discover**: city, a 7-day date strip in the city's time zone, search, genre and language
   filters. Results come from `GET /api/showtimes` and are grouped by movie.
2. **Movie**: showtimes for one movie, grouped by theater.
3. **Seats**: the live seat map (refreshed every 15 s), categories and prices, up to 10 seats.
   Holding seats asks for sign-in if needed.
4. **Checkout**: hold countdown, simulated payment outcome (`SUCCESS`, `DECLINE`, `TIMEOUT`),
   retry after a decline, polling while a payment's outcome is `UNKNOWN`, hold release.
5. **Confirmation**: a digital ticket built from the booking the server returned.

Behavior worth knowing:

- **Credentials are kept in memory only** (React state), never in `localStorage`,
  `sessionStorage`, cookies or IndexedDB, and the bundle contains no credentials. Reloading the
  page signs you out; checkout details (the API has no `GET /holds/{id}`) are also in memory, so
  a reload during checkout returns you to browsing and the hold simply expires.
- **Idempotency keys:** each payment attempt gets one random key. Network failures, gateway
  errors and `SEATS_BUSY` keep the key, and "Retry" resends the identical request, so the
  server's idempotency guarantees a single charge. A new key is created only after the attempt
  was definitively declined. The app never retries a charge with a fresh key after an
  ambiguous result; `UNKNOWN` outcomes are polled via `GET /api/payments/{id}`.
- **Posters** are generated in the browser from each movie title (gradient and shapes chosen by
  a hash; `src/lib/posters.ts`). No studio artwork is shipped, so there are no image rights to
  clear and the app works offline.
- **Fonts** are Inter and Fraunces from the `@fontsource-variable` npm packages (SIL Open Font
  License 1.1), bundled and served locally. Icons are Lucide (ISC license).
- Animations respect the operating system's reduced-motion setting.

Frontend development without Docker (Node 22.18 or later):

```bash
cd frontend
npm ci
npm test            # unit tests (Node's built-in test runner)
npm run build       # type-check and production build
BACKEND_URL=http://localhost:3000 npm run dev   # Vite on :5173, API proxied to the Compose stack
```

## Backend tests

```bash
cd backend
./mvnw -B clean verify   # unit tests + integration tests on Testcontainers PostgreSQL (needs Docker)
```

## Known limitations

- Playwright end-to-end tests are not implemented yet.
- No "my bookings" list (the API has no `GET /bookings`); a ticket is reachable from the
  confirmation page right after booking, or by its URL while signed in.
- The local stack is plain HTTP and binds to `127.0.0.1` only.
