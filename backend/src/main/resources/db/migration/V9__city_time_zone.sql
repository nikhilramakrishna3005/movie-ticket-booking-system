-- Each city's IANA time zone. Search dates, showtime local times, and admin-entered start times
-- are interpreted in the theater's city zone, not in UTC (docs/architecture.md §11).
-- All seeded cities are in Texas, hence America/Chicago. The default only backfills existing
-- rows; it is dropped so that every new city must state its zone explicitly.
ALTER TABLE city ADD COLUMN time_zone text NOT NULL DEFAULT 'America/Chicago';
ALTER TABLE city ALTER COLUMN time_zone DROP DEFAULT;

-- A CHECK cannot look up pg_timezone_names, so only the IANA "Area/Location" shape is enforced
-- here; the application rejects unknown zone ids when it reads them.
ALTER TABLE city ADD CONSTRAINT city_time_zone_ck CHECK (time_zone ~ '^[A-Za-z]+(/[A-Za-z0-9_+-]+)+$');
