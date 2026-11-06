# ADR-0017: Replace each day's snapshot atomically; keep a more complete snapshot when a provider is missing

- **Status:** Accepted
- **Date:** 2026-11-06
- **Related spec:** [specs/004-persistence-and-pipeline](../../specs/004-persistence-and-pipeline/spec.md)
- **Refines:** [ADR-0016](0016-day-partitioned-aggregation.md) (its visibility and "don't wipe a missing provider"
  follow-ups), [ADR-0007](0007-postgresql-and-redis.md)

## Context

Ingestion publishes one horizon day at a time (ADR-0016): ≈ 143,000 itineraries per day at 1M, up to 2 days written
concurrently. Search (next spec) reads the same table while runs write it every 15 minutes. Two requirements:

- **Visibility:** a reader must see a day's previous snapshot or its new one, never a mix or an empty day.
- **Partial days:** a day can arrive without one provider (outage, timeouts). Spec 003 requires that this must not
  silently wipe that provider's previous good data. Rows store only the *selected* offer of each itinerary, so the
  missing provider's own price for an itinerary that another provider also sells is not known.

## Decision

We will treat a **day as the unit of replacement**, written in **one transaction**:
`DELETE FROM itinerary WHERE departure_date = :day`, bulk load the new rows (ADR-0018), upsert `day_snapshot`.
PostgreSQL MVCC gives every statement a consistent snapshot, so readers see either the old or the new rows; the delete
is not visible until commit.

A publication **replaces** the day's snapshot when one of the following is true:
1. the day has no snapshot;
2. the new contributing providers include every provider of the existing snapshot (a complete day always replaces;
   a partial day replaces a previous partial day that is no more complete);
3. the existing snapshot has been stale for longer than `max-stale-age` (2 h).

Otherwise the existing rows are **kept** and the day is marked **stale** (`stale_since`, set by the first run that
could not refresh it). A day where every provider failed is not published (spec 003). Its snapshot is kept and
marked stale the same way. A partial publication is marked **incomplete** with its missing providers. Every decision
is reported per day (`REPLACED`, `PUBLISHED_INCOMPLETE`, `KEPT_STALE`, `NO_DATA`, `WRITE_FAILED`).

After a run's writes, `ANALYZE itinerary` refreshes the planner's statistics, because the snapshots of up to 7 days
have just changed.

## Alternatives considered

1. **Per-provider merge** (spec 003's original wording: keep the missing provider's rows, replace the rest). It is not
   exact: an itinerary that both a contributor and the missing provider sold keeps only one selected offer. So
   whether the missing provider was cheaper is unknown, and the merged row can show the wrong price and provider. We
   rejected it in favour of a rule that never invents data.
2. **An `offer` table per provider, re-aggregated on write.** This is exact: the missing provider's last offers are
   combined with the fresh ones. But it writes ≈ 1.9× the rows (every offer, not every itinerary), and it moves
   deduplication into SQL or re-reads offers into the JVM, duplicating the spec 001 domain. It is worth it only if
   partial days become frequent.
3. **List partitioning by `departure_date`, with a staging table swapped in (`DETACH` / `ATTACH`)** and `DROP` for
   retention. It leaves no dead tuples and makes retention instant. On the other hand it needs DDL on every run,
   `ACCESS EXCLUSIVE` locks that queue behind long searches, and partition management code. At ≈ 1M rows, a delete
   and a vacuum cost little. This is the path if the volume or the write rate grows by an order of magnitude.
4. **Versioned snapshots** (rows tagged with a snapshot id; `day_snapshot` points at the current one; old versions
   deleted later). Short transactions, but every search has to join or filter on the current version, and garbage
   collection becomes a second process.

## Consequences

- **Positive:** atomic per day with plain SQL. No data is ever invented or silently lost, and freshness is explicit
  and queryable (`day_snapshot`), so the dashboard can show it. Retention is one indexed delete.
- **Negative / trade-offs:** while a day is stale, the contributing providers' fresh prices for that day are ignored,
  for at most 2 h. Each replacement leaves ≈ one day of dead tuples for autovacuum. A run's days are published at
  different moments, so for a few seconds two days can come from different runs (each day is consistent). Row ids
  change on each replacement (see below).
- **Follow-ups:** the search spec must document that keyset cursors can skip or repeat results across a replacement,
  and decide whether to show day freshness.
