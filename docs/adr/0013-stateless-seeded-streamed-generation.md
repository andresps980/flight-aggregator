# ADR-0013: Stateless, index-seeded, streamed mock data generation

- **Status:** Accepted
- **Date:** 2026-11-05
- **Related spec:** [specs/002-gds-mock](../../specs/002-gds-mock/spec.md)

## Context

The mock must serve up to 1,000,000 distinct itineraries over a 7-day horizon, offered by 1–3 providers each
(~2M records), with injected invalid itineraries, malformed records and duplicates. The same seed and reference date
must give the same data, so tests and ingestion runs are reproducible. Three endpoints must agree on every shared
itinerary without coordinating, `?date=` must return a single day, and `GET /admin/world/stats` must report exact
expected figures. Memory must stay bounded: a materialised world of a million object graphs would need gigabytes.

## Decision

We will generate the world **on the fly, per request, from a per-itinerary seed**:

- Itinerary *i* of partition day *d* is generated from its own `SplittableRandom(mix(seed, d.epochDay, i))`. All
  of its properties come from that stream: segments, base fare, invalid kind, the providers that offer it, each
  provider's price, malformations and same-provider duplicates.
- A provider request iterates days and indexes lazily, regenerates each itinerary and emits only that provider's
  records, straight to the response serializer (streamed JSON array, StAX XML, line-by-line CSV). Nothing is
  shared or cached between requests. `?date=` iterates one day only.
- **Uniqueness by construction:** within a day, index *i* maps one-to-one to the first segment's (airline, flight
  number), so no two itineraries share an identity. That caps a day at 24 airlines × 9,999 numbers = 239,976
  itineraries, validated at startup.
- Stats are one lazy pass over the same generator with counters, cached per reference date and day.
- The generator is plain Java (no Spring, Jackson or XML), enforced by ArchUnit.

## Alternatives considered

1. **Generate the world once at startup and keep it in memory** — fast responses, but ~1–2 GB of heap at 1M
   itineraries and a slow startup, for a mock. Rejected.
2. **One sequential `Random` over the whole world** — deterministic too, but item *i* depends on all items before
   it, so a single day can't be generated without generating the previous ones, and any change to an item shifts
   every later item. Rejected.
3. **Store the world in a database or files** — persistent and queryable, but adds infrastructure to a test double
   and still needs a generator. Rejected.

## Consequences

- **Positive:** O(1) memory at any volume; byte-identical responses for the same inputs; providers agree with no
  shared state; per-day partitions are cheap and disjoint; the expected figures come from the very code that
  produces the data.
- **Negative / trade-offs:** every request regenerates the whole world (CPU: each provider call walks all
  itineraries, including ones it doesn't offer). Records come in generation order, not shuffled. Changing the
  generator code changes the data for a given seed. Uniqueness via the first flight number is artificial (real
  flight numbers repeat across itineraries).
- **Follow-ups:** measure generation throughput at 1M and record it in `SOLUTION.md`.
