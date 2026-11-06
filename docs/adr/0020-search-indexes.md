# ADR-0020: Minimal search index set, chosen by EXPLAIN on 1M rows

- **Status:** Proposed (accepted once the measurements below are recorded, in this PR)
- **Date:** 2026-11-06
- **Related spec:** [specs/005-search-api](../../specs/005-search-api/spec.md) (AC-22, AC-23)
- **Refines:** [ADR-0008](0008-keyset-pagination.md) ("composite indexes cover the filter columns followed by
  `(price, id)`"), [ADR-0018](0018-bulk-load.md) (follow-up: re-measure `COPY` with the search indexes)

## Context

A search page is one query (spec 005, ADR-0008):

```sql
SELECT … FROM itinerary
WHERE departure_at > :now [AND departure_date = :date] [AND origin = :o] [AND destination = :d]
  [AND airline = :c] [AND final_price <= :max] [AND (final_price, id) > (:p, :id)]
ORDER BY final_price, id LIMIT :limit + 1
```

Every filter is optional, so the combinations are 2⁵. One composite index per combination would make every `COPY`
of a day (ADR-0018, 10–14 s for 1M rows with two indexes) pay for indexes most searches never use, and each index
costs ≈ 30–60 MB at 1M rows. The data shape decides what is cheap: ≈ 35 airports and 24 airlines in gds-mock, so at
1M rows a route has ≈ 800 rows, an origin ≈ 28k, a carrier ≈ 40k, a day ≈ 143k; prices spread over a wide range.

Two plan shapes are acceptable (AC-22):
- **Ordered scan + `LIMIT`**: an index whose order is `(… equality columns, final_price, id)`; the scan seeks the
  cursor position and stops after `limit + 1` matching rows. Residual filters (columns not in the index) are cheap
  while they are not very selective: the scan reads ≈ `limit / selectivity` entries.
- **Small set + sort**: an index on the equality columns returns the whole matching set, which is sorted in memory.
  Fine when the set is small (one route ≈ 800 rows), wasteful when it is large.

## Decision

We will start from the **smallest candidate set** and add an index only when `EXPLAIN (ANALYZE, BUFFERS)` on 1M rows
(gds-mock-shaped data, `ANALYZE`d) shows a common combination needing it:

1. **`(final_price, id)`** — serves the unfiltered search and any non-selective filter (date, carrier, origin) as
   a residual on the price-ordered scan, in keyset order, at any depth; `maxPrice` becomes the scan's upper bound.
2. **`(origin, destination, final_price, id)`** — serves the main combination (a route, with or without date,
   carrier, `maxPrice`) in keyset order; and `origin` alone through its prefix as a small set + sort if measured
   acceptable.

Candidates evaluated against these two: `(origin, final_price, id)`, `(airline, final_price, id)`,
`(departure_date, final_price, id)`, and a partial index on future rows (rejected up front: "now" moves, so the
predicate cannot be fixed in the index). `departure_at > :now` is always a residual (it removes at most part of
today).

The worst realistic case — the busiest route, no date, a `maxPrice` above every price — and deep pages are in the
`slow` performance test (AC-23).

**Measurements** (filled in when implemented; plans, buffers and timings per combination, `COPY` 1M rows before and
after): *pending*.

## Alternatives considered

1. **One composite index per filter combination** — every query gets a perfect ordered scan, but 5–10 indexes would
   multiply the `COPY` and replacement time and the table size for little gain at this volume. Rejected.
2. **Only `(final_price, id)`** — one index, but a selective filter (a route ≈ 0.08 % of rows) would scan ≈ 25,000
   entries for a page of 20, and a route with no match would scan the whole index. Rejected unless measured fine.
3. **Separate single-column indexes combined by bitmap scans** — the planner can `BitmapAnd` them, but a bitmap scan
   loses the index order, so every page sorts the whole matching set (≈ 143k rows for a day). Rejected.
4. **A search engine / precomputed per-route pages** — see ADR-0007; out of scope.

## Consequences

- **Positive:** few indexes, so the bulk load stays fast; every common search is an index scan bounded by `LIMIT`
  or by a small set; the choice is backed by recorded plans, not intuition.
- **Negative / trade-offs:** a filter that is both selective and not in an index (e.g. a rare carrier with no route)
  scans more of the price index; very selective combinations with an unusually low `maxPrice` can read many entries
  before finding `limit` rows. New filters or sort orders need this analysis again.
- **Follow-ups:** record the measured plans and `COPY` times here and in SOLUTION.md; revisit if the catalog grows
  (more airports make routes smaller and the price index less useful for route searches).
