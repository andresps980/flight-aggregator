# ADR-0018: Bulk load of day snapshots

- **Status:** Proposed (the choice is finalised with the measurements below in this PR)
- **Date:** 2026-11-06
- **Related spec:** [specs/004-persistence-and-pipeline](../../specs/004-persistence-and-pipeline/spec.md)
- **Refines:** [ADR-0007](0007-postgresql-and-redis.md) ("`JdbcClient` batching")

## Context

Each run rewrites up to 7 day snapshots (ADR-0017): ≈ 946,000 rows at 1M, ≈ 143,000 per day, 13 columns, including
one JSONB and one array. The target is to load 1M rows **well under a minute**, without materialising a day as one
big buffer in the heap (the heap should only hold the ingestion working set). ADR-0007 assumed `JdbcClient` batching.
PostgreSQL's `COPY` is the usual faster path, but it is PostgreSQL-specific and needs its own encoding.

## Decision

We will **measure both** on the same 1M-row dataset (Testcontainers `postgres:17`, `slow`-tagged test) and keep the
faster one:

- **A. `COPY itinerary (...) FROM STDIN`** through PgJDBC's `CopyManager`. Rows are encoded in text format into a
  small buffer that is flushed to `CopyIn` as it fills, so no day is materialised. The encoder escapes `\`, tab,
  newline and carriage return, writes arrays and JSONB as text literals, and writes `\N` for null.
- **B. `INSERT ... VALUES` batches** (`JdbcTemplate.batchUpdate`, batch size ~1,000) with PgJDBC
  `reWriteBatchedInserts=true`, which rewrites a batch into multi-row inserts.

Either way the load runs inside the day's transaction (ADR-0017). The code lives only in
`infrastructure/adapter/out/persistence`, and an ArchUnit rule keeps `org.postgresql` there.

*Measurements and final choice: to be completed in this PR.*

## Alternatives considered

1. **JPA / Hibernate `saveAll`** — already rejected in ADR-0007 (persistence context, per-entity overhead).
2. **Unlogged staging table + `INSERT ... SELECT`** — faster writes, but it adds a table and a second copy of the
   data, and would only pay off if A or B missed the target.
3. **Binary `COPY`** — saves parsing on the server side, but needs a hand-written binary encoding for `numeric`,
   arrays and JSONB. Worth it only if text `COPY` is the bottleneck.

## Consequences

- To be completed with the measurements.
