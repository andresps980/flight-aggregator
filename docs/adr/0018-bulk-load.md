# ADR-0018: Bulk load day snapshots with PostgreSQL COPY

- **Status:** Accepted
- **Date:** 2026-11-06
- **Related spec:** [specs/004-persistence-and-pipeline](../../specs/004-persistence-and-pipeline/spec.md)
- **Refines:** [ADR-0007](0007-postgresql-and-redis.md) (which assumed "`JdbcClient` batching")

## Context

Each run rewrites up to 7 day snapshots (ADR-0017): ≈ 946,000 rows at 1M, ≈ 143,000 per day, 15 columns including one
JSONB and one array. The target is to load 1M rows **well under a minute**, without materialising a day as one big
buffer in the heap (the heap should only hold the ingestion working set). ADR-0007 assumed `JdbcClient` batching;
PostgreSQL's `COPY` is the usual faster path, but it is PostgreSQL-specific and needs its own encoding.

## Decision

We measured both techniques on the same 1,000,006 rows (7 days × 142,858, a third with two segments), in the
Testcontainers `postgres:17` the build uses (Apple M1 Max, Docker with 6 CPUs / 16 GB), through the real store
(each day in its own transaction, with the `departure_date` index and the unique key in place):

| Technique | First load | Replacement (delete + load, every later run) |
|---|---:|---:|
| **`COPY ... FROM STDIN` (text format)** | **10.4 s** | **13.8 s** |
| Batched `INSERT` (1,000 per batch), `reWriteBatchedInserts=true` | 18.9 s | 24.5 s |
| Batched `INSERT` (1,000 per batch), without rewriting | 31.0 s | 34.7 s |

We will load with **`COPY itinerary (...) FROM STDIN`** through PgJDBC's `PGCopyOutputStream`:

- `ItineraryCopyEncoder` writes each row as a line of the text format into a 64 KiB buffer that is flushed to the
  copy stream as it fills, so a day is never materialised. It escapes `\`, tab, newline and carriage return, writes
  `providers` as a quoted array literal and `segments` as JSON (hand-written: it runs once per row). No column is null.
- The load runs on the day's transaction connection (ADR-0017); a failure cancels the copy and rolls the day back.
- PostgreSQL-specific code (`org.postgresql`, `COPY`, advisory locks) lives only in
  `infrastructure/adapter/out/persistence`, enforced by an ArchUnit rule.
- The batched loader stays in test sources, so `BulkLoadBenchmarkIT` (`slow` tag) can repeat the comparison.

## Alternatives considered

1. **Batched `INSERT` with `reWriteBatchedInserts`** — portable JDBC and simpler code, but 1.8× slower (above). It
   would still meet the target, so it is the fallback if `COPY` ever became a problem (e.g. a proxy that does not
   support the copy sub-protocol).
2. **JPA / Hibernate `saveAll`** — already rejected in ADR-0007 (persistence context, per-entity overhead).
3. **Unlogged staging table + `INSERT ... SELECT`** — faster writes, but an extra table and a second copy of the data;
   only worth it if `COPY` missed the target.
4. **Binary `COPY`** — saves server-side parsing, but needs a hand-written binary encoding for `numeric`, arrays and
   JSONB; worth it only if text `COPY` were the bottleneck.

## Consequences

- **Positive:** 1M rows in ≈ 10–14 s, a quarter of the budget; constant memory per day (one 64 KiB buffer); the
  whole day still commits or rolls back atomically.
- **Negative / trade-offs:** a hand-written encoder whose escaping must be right (covered by unit tests and a
  round-trip IT with hostile values); PostgreSQL-specific; `COPY` does not report per-row errors, so one bad row
  fails the whole day (reported as `WRITE_FAILED`, previous snapshot intact).
- **Follow-ups:** the search spec adds its indexes and re-measures the load with them (each index slows `COPY`).
