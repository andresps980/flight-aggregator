# ADR-0007: PostgreSQL as source of truth, Redis as search cache

- **Status:** Accepted
- **Date:** 2026-11-04
- **Related spec:** [specs/004](../../specs/004-persistence-and-pipeline/spec.md), [specs/005](../../specs/005-search-api/spec.md); cache keying in [ADR-0022](0022-search-cache.md)

## Context

Each ingestion run (ADR-0006) writes up to ~1,000,000 deduplicated itineraries, and the search API filters them by
origin, destination, max price and carrier with stable pagination (ADR-0008). We need fast bulk writes, indexed
range queries, schema versioning, and low-latency repeated reads. The stack must start with `docker compose up --build`.

## Decision

We will use:

- **PostgreSQL** as the source of truth for itineraries and ingestion runs.
  - Schema managed with **Flyway** migrations.
  - Access through Spring's **`JdbcClient`** with **batched** inserts/upserts; **no JPA/Hibernate**.
  - Indexes designed for the search filters and the keyset order `(price, id)`.
- **Redis** as a **cache of search results** (pages keyed by normalised filters + cursor), invalidated when an
  ingestion run publishes new data. The cache is an optimisation: a Redis outage degrades to database reads.

Both are driven ports' adapters in `infrastructure/adapter/out`; the domain and use cases do not know about SQL or Redis.

## Alternatives considered

1. **JPA/Hibernate** — persistence context and per-entity dirty checking add memory and CPU overhead on million-row
   batches, and the search queries are hand-tuned SQL anyway. Rejected.
2. **Redis only** — no relational filtering, range queries or durable source of truth; secondary indexes would be
   hand-built. Rejected.
3. **Elasticsearch / OpenSearch** — strong for full-text search, but the filters here are simple equality/range, and
   it adds a heavy service to the compose stack. Rejected for this scope.
4. **In-memory store in the JVM** — loses data on restart and doesn't scale beyond one instance. Rejected.

## Consequences

- **Positive:** predictable bulk-load performance; explicit, reviewable SQL; versioned schema; cheap repeated searches.
- **Negative / trade-offs:** more hand-written SQL and mapping code; cache invalidation must be correct (stale pages
  after a run are the main risk); two infrastructure services to run and test (Testcontainers in ITs).
- **Follow-ups:** table design and indexes in the ingestion/search specs; cache key and TTL policy in the search spec.
