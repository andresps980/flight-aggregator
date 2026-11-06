# ADR-0010: Domain assumptions for itineraries, deduplication and pricing

- **Status:** Accepted
- **Date:** 2026-11-04
- **Related spec:** [specs/001-itinerary-aggregation](../../specs/001-itinerary-aggregation/spec.md) (refines the rule
  list: equal arrival/departure is invalid, `MIXED_AIRLINES` and `REVISITED_AIRPORT` are discard reasons, fixed
  evaluation order); [specs/005-search-api](../../specs/005-search-api/spec.md) (amends item 6)

## Context

The statement leaves several business rules open: what a "dead leg or invalid connection" is, currencies, multi-
airline itineraries, how the 5% markup is computed, and how dates are filtered. These rules live in the domain
(framework-free, ADR-0002) and must be explicit so they can be tested and challenged.

## Decision

We will assume:

1. **Invalid itineraries are discarded during ingestion.** An itinerary is a dead leg / invalid connection when:
   - its segments **don't chain** (a segment's origin differs from the previous segment's destination);
   - a **connection time is below 45 minutes**;
   - a **layover exceeds 24 hours**;
   - a segment **arrives before it departs**;
   - the **flight has already departed** at ingestion time.
2. **Identity for deduplication:** same airline, same ordered flight numbers, same connections (airports) and same
   departure times. Among duplicates from different providers, the **cheapest** offer wins.
3. **Single currency: EUR.** All provider prices are EUR; no conversion.
4. **One airline per itinerary.** Every segment of an itinerary is operated by the same carrier (the carrier filter
   matches that airline).
5. **Markup:** a configurable OTA markup, **5% by default**, applied to the selected cheapest price. Money is
   modelled with **`BigDecimal`** and rounded to 2 decimals with **`HALF_EVEN`** (banker's rounding).
6. **Date filter:** search accepts an optional departure date (UTC day of the first departure). *Amended by
   [spec 005](../../specs/005-search-api/spec.md) (2026-11-06):* when absent, search returns **every future
   departure** (first departure strictly after the request instant), not just the request's date; with a date, that
   UTC day only. **Departed itineraries are never returned**: the "already departed" rule of item 1 is applied again
   at query time, because stored data can be up to one ingestion interval old.

## Alternatives considered

1. **Keep invalid itineraries and flag them** — they could never be sold, and they would inflate storage and search
   results at 1M-row scale. Rejected.
2. **Multi-currency with conversion** — requires an FX source and a reference currency for "cheapest"; no provider
   data suggests it. Rejected for this scope.
3. **`double` for prices / `HALF_UP` rounding** — floating point is unsafe for money; `HALF_EVEN` avoids systematic
   upward bias over millions of rows. Rejected.

## Consequences

- **Positive:** rules are explicit, testable domain invariants; deterministic dedup and pricing.
- **Negative / trade-offs:** interline (multi-airline) itineraries and non-EUR fares are out of scope; the 45 min /
  24 h thresholds are fixed assumptions, not airport-specific minimum connection times.
- **Follow-ups:** encode each rule as an acceptance criterion in the aggregation spec; list them as limitations in
  `SOLUTION.md`.
