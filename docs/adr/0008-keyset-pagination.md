# ADR-0008: Keyset pagination ordered by (price, id)

- **Status:** Accepted
- **Date:** 2026-11-04
- **Related spec:** to be written (search)

## Context

The search API must page through up to ~1,000,000 itineraries with filters (origin, destination, max price,
carrier), and the statement asks to justify keyset vs. offset pagination. Users browse cheapest-first, and data is
replaced by periodic ingestion runs (ADR-0006).

## Decision

We will use **keyset (seek) pagination** ordered by **`(price ASC, id ASC)`**:

- The next page is requested with an **opaque cursor** encoding the last `(price, id)` returned; the query uses
  `WHERE (price, id) > (:price, :id) ... ORDER BY price, id LIMIT :size + 1` (the extra row tells whether there is a
  next page).
- `id` is the unique tie-breaker, so the order is total and deterministic even with equal prices.
- Composite indexes cover the filter columns followed by `(price, id)`.
- The page size is bounded by a configurable maximum.

## Alternatives considered

1. **Offset pagination (`LIMIT/OFFSET`)** — the database scans and discards `offset` rows, so deep pages are O(n)
   (page 10,000 of 100 rows reads ~1M rows); rows inserted/removed between requests cause duplicates or gaps.
   Rejected.
2. **Page number + total count** — `COUNT(*)` over a million filtered rows on every request is expensive and the
   dashboard does not need "jump to page N". Rejected.

## Consequences

- **Positive:** constant cost per page regardless of depth (index seek); stable results while data changes;
  cache-friendly page keys (ADR-0007).
- **Negative / trade-offs:** no random access to page N and no exact total count; sorting is restricted to the
  indexed key (other sort orders would need their own index and cursor format); cursors from a previous ingestion
  snapshot may point to rows that no longer exist (the seek still works, it just continues from that position).
- **Follow-ups:** cursor encoding and validation (invalid cursor → `400` ProblemDetail) in the search spec.
