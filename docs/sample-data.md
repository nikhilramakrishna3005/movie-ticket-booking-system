# Sample Data

> **Status:** written in `V7__sample_data.sql`. Whether it loads has **not** been verified with
> Testcontainers, because Docker was unavailable in the authoring environment. See PR #2 for
> what was actually run.

The demo data is compact and **deterministic**: every run of the migrations produces the same
rows. It does not depend on the date the migrations run, and it contains no `NOW()` calls.

## Contents

| Data | Amount |
|---|---|
| Languages | 5 (en, es, hi, ja, ko) |
| Genres | 10 |
| Movies | 8 (well-known released films, each with 1–3 genres) |
| Cities | 3 (Dallas, Houston, Austin — all TX, all US Central time) |
| Theaters | 5 (fictional names) |
| Screens | 6: three large (6 rows × 10 seats), three small (5 rows × 8 seats) |
| Seats | 300 physical seats. On every screen the last two rows are `PREMIUM`; the rest are `STANDARD`. |
| Showtimes | 1,080: every screen, every day, 2 shows per day |
| Seat inventory (`show_seat`) | 54,000 rows, all `AVAILABLE`. Prices: $12.50 standard, $16.00 premium. |

**Users** are seeded separately by `V8__demo_users.sql`: two customers (`alice`, `bob`) and two
theater admins, `admin.dallas` (assigned to Starlight Cinemas Downtown) and `admin.houston`
(assigned to Bayou Picture House). Their passwords are fictional, public, and listed in the
README's local-only demo accounts section; only BCrypt hashes are stored.

## Validity window for the live demo

- Sample showtimes run **from 2026-11-03 through 2027-01-31 inclusive**, at **13:00 and 16:30
  America/Chicago** (UTC−6 for the whole window). The assessment date is 2026-11-02, so every
  sample showtime is in the future at assessment time.
- Show times were chosen so that the **UTC date equals the local date**. The MVP search filters
  by UTC date (`docs/product-scope.md` §7), so searching for a calendar date returns that day's
  local shows.
- Shows that have already started cannot be held (AC-H9). On any demo day within the window,
  the 13:00 and 16:30 shows of that day and all later days are usable until they start.
- **If the demo happens after 2027-01-31**, or close to the end of the window: add a **new**
  migration (e.g. `V<next>__extend_sample_showtimes.sql`) that inserts showtimes and their
  `show_seat` rows for a later window, using the same pattern as V7. Never edit `V7`; Flyway
  checksums would fail and the history would no longer be truthful.
- Tests must not depend on the wall clock relative to this window. Tests that need a "now" use
  a fixed test `Clock`, and tests that need specific showtimes (past, cancelled, overlapping)
  insert their own rows.

## Known simplifications

- All sample cities are in one time zone. There is no per-theater time zone column; the
  UTC-date search simplification would show late-evening local shows under the next UTC date.
- Seed data is part of the normal migration set, so it is also loaded into any database the
  application is pointed at. This is acceptable for a local-only assessment; a real deployment
  would separate reference data from demo data.
