# ADR-0016: Aggregate one horizon day at a time, with summed statistics

- **Status:** Accepted
- **Date:** 2026-11-05
- **Related spec:** [specs/003-provider-ingestion](../../specs/003-provider-ingestion/spec.md)
- **Refines:** [ADR-0011](0011-incremental-deduplication.md) (its "partition the aggregation" follow-up)

## Context

Partitions of the three providers complete concurrently on virtual threads, but `OfferAggregator` is single-use and
not thread-safe (ADR-0011). One global aggregator holds every distinct itinerary of the run: ~1–1.5 GB at 1,000,000
(an estimate), plus the buffers of partitions in flight. The itinerary identity includes departure instants, and
providers partition their feeds by the UTC date of the first departure, so two offers of the same itinerary are
always in the same day partition: duplicates never cross days.

## Decision

We will aggregate **per horizon day**:

- For each day, a coordinator fetches that day from every provider in parallel and feeds a **dedicated
  `OfferAggregator`**. The coordinator is the **single consumer**: it takes each partition as soon as it completes
  (a completion queue) and feeds it on its own thread, so no lock is needed and each buffer is released once fed.
- When all of the day's partitions have finished, the day's result is published to `DayAggregationSink` with the
  contributing and missing providers, and the aggregator is released. A day with no successful partition is not
  published.
- Up to `max-concurrent-days` days run at once (default 2); `max-concurrent-partitions` per provider (default 2) caps
  provider load independently.
- The run statistics are the **sum** of the day statistics (`AggregationStatistics.plus`). Every counter is
  additive, and day key sets are disjoint, so the sum equals one aggregator fed with everything. All days share one
  validator, whose clock is read once per run (ADR-0011).

**Assumption:** providers partition by UTC date of first departure (true for gds-mock). If one didn't, an itinerary
served under two days would appear twice: a visible duplicate, never a lost offer.

## Alternatives considered

1. **One global aggregator behind a lock** — simple and correct, but memory is the whole world (~1.5–2 GB at 1M with
   buffers) and nothing can be published before the end of the run.
2. **One global aggregator fed by a single consumer thread through a queue** — same memory as (1), plus a queue to
   bound.
3. **Hash-partitioned aggregators (by key)** — also correct and additive, but every partition needs every provider's
   whole feed before it can finish, so memory is not reduced; the day split comes for free from the feed structure.

## Consequences

- **Positive:** memory is bounded by the days in flight (≲ 1 GB at 1M with 2 days, a tenth at 100k) instead of by
  the world; results are published day by day, which fits a database upsert per day; no shared mutable state between
  threads.
- **Negative / trade-offs:** relies on the partitioning assumption; a day's itineraries can be published while other
  days are still running, so a reader may briefly see a mix of runs (the persistence spec decides visibility);
  memory still grows with the size of the largest day.
- **Measured (1M itineraries, SOLUTION.md):** the working set is about 250 MB per day in flight (512 MB with the
  default 2 days, 1 GB with all 7). That is below the estimate above, because each partition is fed and dropped as
  it arrives.
- **Follow-ups:** the persistence spec must not wipe a missing provider's previous data for a day (spec 003).
