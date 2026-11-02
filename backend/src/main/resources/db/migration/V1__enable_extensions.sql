-- pg_trgm: typo-tolerant search on movie titles and theater names (docs/architecture.md §11).
-- btree_gist: lets the showtime exclusion constraint combine screen equality with time-range overlap.
-- Both are trusted extensions in PostgreSQL 13+, so the database owner can create them.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;
