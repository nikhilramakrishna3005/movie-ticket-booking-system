-- Search indexes (docs/architecture.md §11). pg_trgm trigrams are case-insensitive, so the
-- GIN indexes serve similarity (%) and ILIKE queries on the raw columns.
CREATE INDEX movie_title_trgm_idx ON movie USING gin (title gin_trgm_ops);
CREATE INDEX theater_name_trgm_idx ON theater USING gin (name gin_trgm_ops);

-- Date filter: search only returns scheduled showtimes within a date range.
CREATE INDEX showtime_scheduled_starts_at_idx ON showtime (starts_at) WHERE status = 'SCHEDULED';
