# ADR 0003: Defer Apache Solr (typo-tolerant search)

- **Status:** Proposed
- **Date:** 2026-11-02 (revised 2026-11-02 after review of PR #1)
- **Deciders:** _Pending human review_

## Context / Problem

Customers search by movie name, theater name, city, language, genre, and date, and search must
**tolerate typos** (e.g. "Intersteller" → "Interstellar"). Design target: 2,000 theaters across
300 cities, with heavy read traffic during major releases.

The searchable corpus is modest: thousands of theaters, hundreds to low thousands of active
movies, and showtimes filtered primarily by city and date (structured filters), with free text
applied to short fields (titles and theater names).

## Alternatives considered

| Option | Summary |
|---|---|
| A. Apache Solr (or Elasticsearch/OpenSearch) | Dedicated search engine: fuzzy matching, relevance tuning, facets, synonyms. Requires indexing pipeline. |
| B. PostgreSQL `pg_trgm` trigram similarity + B-tree filters | Typo tolerance via trigram similarity with GIN indexes; filters use ordinary indexed columns. Same database, no sync. |
| C. PostgreSQL full-text search (`tsvector`) | Good for stemming/longer text; weak for typos in short names. |
| D. `LIKE`/`ILIKE` only | Simple; no typo tolerance. Does not meet requirements. |

## Decision

**Option B for the assessment build. Solr is deferred.**

- Enable `pg_trgm`; add GIN trigram indexes on movie title and theater name.
- Combine trigram similarity (ranking + threshold) with structured filters for city, language,
  genre, and showtime date.
- Keep the search in a single query in the `catalog` package. No interface is introduced
  speculatively. Replacing it later would mean changing one class, plus adding an indexing
  pipeline.

## Tradeoffs

**Why not Solr now:**
- Requires a second data store and an **indexing/sync pipeline** (batch reindex, change events,
  or the outbox from ADR 0002), with eventual consistency between catalog and index.
- Adds a JVM service with its own schema configuration, memory tuning, and test setup — a poor
  fit for a 10-hour budget given the small, structured corpus.

**Costs of deferring:**
- Less sophisticated relevance tuning, no built-in synonyms/stemming per language, fewer
  faceting/analytics features.
- Search queries load the primary PostgreSQL database (mitigable with read replicas).
- Trigram matching quality on very short strings (2–3 characters) is limited.

## Operational cost (if adopted)

- Solr cluster (SolrCloud + ZooKeeper, or a managed service), heap sizing, schema/config management.
- An indexer that keeps the index consistent with PostgreSQL, including full reindex procedures.
- Monitoring of index freshness and query latency; additional container in Docker Compose and tests.

## When Solr could become justified

- Product requirements for richer search: multilingual analysis, synonyms ("LOTR"), autocomplete
  at scale, faceted navigation, personalized ranking.
- Measured search latency or load on PostgreSQL that read replicas and query tuning cannot fix.
- Search corpus grows substantially (e.g. cast/crew, descriptions, reviews).
- Even then, Solr would serve **discovery only**; seat availability and ownership would continue
  to be read from PostgreSQL at hold time.
