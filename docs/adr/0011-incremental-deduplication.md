# ADR-0011: Incremental deduplication keyed by a deterministic itinerary key

- **Status:** Accepted
- **Date:** 2026-11-05
- **Related spec:** [specs/001-itinerary-aggregation](../../specs/001-itinerary-aggregation/spec.md)

## Context

Each ingestion run (ADR-0006) receives 100,000 – 1,000,000 offers from three providers. Offers for the same flight
combination (same airline, flight numbers, airports and departure times — ADR-0010) must be collapsed into one
itinerary with the cheapest offer. Providers return results at different speeds and in different formats, and later
parsers will stream records. The result must be deterministic (same offers ⇒ same output, regardless of arrival order)
so runs are reproducible and testable, and the identity must be usable later as a database key.

## Decision

We will:

1. **Model times as `Instant`.** Connection times are only correct on absolute time; provider adapters convert local
   times with their offsets.
2. **Derive an `ItineraryKey` from the identity fields only**: a canonical string of the ordered segments,
   `FLIGHT:ORIGIN-DESTINATION@DEPARTURE` joined by `|` (e.g. `IB3456:MAD-BCN@2026-11-10T08:00:00Z|IB3457:BCN-LHR@…`).
   Flight numbers carry the airline code, so the airline is part of the key. Provider, price and arrival times are not.
3. **Deduplicate with an incremental accumulator** (`OfferAggregator`): `accept(offer)` validates the offer and keeps,
   per key, only the preferred offer and the set of providers; `result()` applies the markup and returns itineraries
   plus statistics.
4. **Prefer offers with a total order** (`OfferPreference`): lowest base price → provider id → earliest arrival times
   (segment by segment) → canonical itinerary representation. Keeping the minimum of a total order is independent of
   input order.

## Alternatives considered

1. **Collect all offers, then group (`Collectors.groupingBy`)** — simplest code, but holds every offer in memory
   (O(offers)) and needs the whole input before producing anything. Rejected.
2. **Deduplicate in the database (`INSERT … ON CONFLICT DO UPDATE WHERE price < excluded.price`)** — scales without
   JVM memory, but moves a core business rule (selection, tie-break, markup) into SQL, outside the framework-free
   domain the statement requires. Kept as a fallback if memory becomes a constraint.
3. **Hash (e.g. SHA-256) as the key** — fixed size, but opaque for debugging; the readable key can be hashed by the
   persistence adapter if needed.
4. **First-received wins on ties** — simpler, but results would depend on provider latency. Rejected.

## Consequences

- **Positive:** memory is O(distinct valid itineraries) instead of O(offers); offers can be fed from streaming parsers;
  output is deterministic; the key doubles as a natural unique key for persistence. Because selection is a "minimum"
  and statistics are counters, disjoint partitions by key hash can be aggregated in parallel and their results
  concatenated, with statistics added.
- **Negative / trade-offs:** at 1M distinct itineraries the accumulator holds ~1M entries in the heap (order of a few
  hundred MB with the key strings); the accumulator is single-threaded by design. The readable key is longer than a hash.
- **Follow-ups:** partitioning and parallel aggregation in the ingestion spec if measurements require it; key hashing
  and the unique index in the persistence spec.
