# Spec 001: Itinerary aggregation core domain

- **Status:** Implemented
- **Branch:** feat/001-itinerary-aggregation
- **Related ADRs:** [ADR-0002](../../docs/adr/0002-hexagonal-architecture.md),
  [ADR-0006](../../docs/adr/0006-scheduled-pre-ingestion.md), [ADR-0010](../../docs/adr/0010-domain-assumptions.md),
  [ADR-0011](../../docs/adr/0011-incremental-deduplication.md)

## Context

From the [statement](../../docs/exercise.md): *"Identify identical itineraries (same airline, flight numbers,
connections, and departure times) offered by different providers. Your core domain must select the cheapest option,
apply a default 5% OTA operational markup, and filter out dead legs or invalid connections."* The system must handle
100,000 – 1,000,000 combinations per day.

This spec delivers that core domain as plain Java, so later specs (ingestion, persistence, search) can feed it offers
parsed from any provider format and store its output. ADR-0010 fixes the business assumptions used here.

## Scope

**In scope**
- Value objects for flight offers: airports, airlines, flight numbers, segments, itineraries, money, providers.
- A deterministic itinerary identity (key) used for deduplication.
- Validation rules that discard dead legs / invalid connections with a reason.
- Deduplication of offers into aggregated itineraries (cheapest base price wins, deterministic tie-break).
- OTA markup (5% by default, configurable) applied to the selected base price.
- Aggregation statistics.
- Removal of the template's example `greeting` feature (code, tests, spec 000, usage docs), keeping a minimal
  integration test that boots the context and checks `/actuator/health`.

**Out of scope**
- Provider formats and parsing (JSON/XML/CSV), ingestion runs, scheduling, HTTP, persistence, Redis, Spring wiring
  and configuration properties (the markup percentage is a constructor argument here; binding it to configuration
  belongs to the ingestion spec).
- Partitioning / merging several aggregations (the design must allow it; see *Non-functional*).
- Multi-currency, interline (multi-airline) itineraries, round trips as a single itinerary, airport-specific minimum
  connection times (ADR-0010).

## Acceptance criteria

Times below are absolute instants (UTC). `now` is the instant of the injected `java.time.Clock`. Amounts are EUR.

**Identity**
- **AC-1** — Given two offers from different providers whose itineraries have the same airline, the same ordered flight
  numbers, the same segment airports and the same departure times, when they are aggregated, then they produce a single
  aggregated itinerary that lists both providers.
- **AC-2** — Given two offers that differ in any one identity field (a flight number, a segment airport, or a departure
  time), when they are aggregated, then they produce two aggregated itineraries.
- **AC-3** — Given two itineraries that are identical in the identity fields, then their itinerary keys are equal; the
  key does not depend on the provider, the price or the arrival times, and the same itinerary always yields the same key.

**Selection** — duplicates are ordered by: (1) lowest base price, (2) provider id ascending (lexicographic),
(3) earliest arrival times, compared segment by segment, (4) canonical itinerary representation ascending. The first
one is selected. The result never depends on the order in which offers are accepted.
- **AC-4** — Given duplicate offers with base prices 120.00, 99.90 and 105.00, when aggregated in any order, then the
  99.90 offer is selected.
- **AC-5** — Given duplicate offers with the same lowest base price from `mock-beta` and `mock-alpha`, when aggregated in
  any order, then the `mock-alpha` offer is selected.
- **AC-6** — Given duplicate offers from the **same** provider (`mock-gamma` at 80.00 and at 75.00), when aggregated, then
  they are merged into one aggregated itinerary with the 75.00 offer selected, and the provider is listed once.
- **AC-7** — Given duplicate offers with the same provider and price whose arrival times differ (a data inconsistency,
  since arrival is not part of identity), when aggregated in any order, then the offer with the earliest arrival is
  selected.
- **AC-8** — Given base prices that differ only beyond the 2nd decimal (99.904 and 99.896, both normalised to 99.90),
  then they tie on price and the selection falls to the next tie-break.

**Markup**
- **AC-9** — Given the default markup policy and a selected base price of 100.00, then the final price is 105.00 and the
  aggregated itinerary exposes both the base price and the final price.
- **AC-10** — Given a markup percentage of 10, when 100.00 is priced, then the final price is 110.00; with 0, the final
  price equals the base price.
- **AC-11** — Given the default 5% markup, when the result needs rounding, then it is rounded to scale 2 with `HALF_EVEN`:
  10.10 → 10.60 (10.605), 10.30 → 10.82 (10.815), 0.01 → 0.01.
- **AC-12 (error)** — Given a negative markup percentage, when the policy is created, then it is rejected with a domain
  validation error.

**Validation (dead legs / invalid connections)** — an invalid offer is discarded, counted under its reason and never
takes part in deduplication.
- **AC-13** — Given an itinerary whose segments are operated by more than one airline, then it is discarded with
  `MIXED_AIRLINES`.
- **AC-14** — Given a segment whose arrival is not after its departure (equal or earlier), then it is discarded with
  `ARRIVAL_NOT_AFTER_DEPARTURE`.
- **AC-15** — Given an itinerary where a segment's origin differs from the previous segment's destination, then it is
  discarded with `SEGMENTS_NOT_CHAINED`.
- **AC-16** — Given an itinerary that visits the same airport twice (e.g. MAD→BCN→MAD→LHR), then it is discarded with
  `REVISITED_AIRPORT`. The visited airports are the first origin followed by every segment destination.
- **AC-17** — Given a connection (next departure − previous arrival) shorter than 45 minutes, including negative ones,
  then it is discarded with `CONNECTION_TOO_SHORT`; exactly 45 minutes is valid.
- **AC-18** — Given a layover longer than 24 hours, then it is discarded with `LAYOVER_TOO_LONG`; exactly 24 hours is valid.
- **AC-19** — Given an itinerary whose first departure is not after `now`, then it is discarded with `ALREADY_DEPARTED`.
- **AC-20** — Given an itinerary that breaks several rules, then it is discarded once, with the reason of the first failing
  rule in this evaluation order — structural first, then timing, then time-relative:
  1. `MIXED_AIRLINES`, 2. `ARRIVAL_NOT_AFTER_DEPARTURE`, 3. `SEGMENTS_NOT_CHAINED`, 4. `REVISITED_AIRPORT`,
  5. `CONNECTION_TOO_SHORT`, 6. `LAYOVER_TOO_LONG`, 7. `ALREADY_DEPARTED`.
- **AC-21** — Given a valid direct (one-segment) or multi-segment itinerary in the future, then it passes validation.

**Statistics**
- **AC-22** — Given a mix of valid, invalid and duplicate offers from several providers, when the result is produced, then
  the statistics report: offers received per provider (valid or not), offers discarded per reason, duplicates merged
  (valid offers that did not become a new itinerary) and itineraries produced; and
  `Σ received = Σ discarded + duplicates merged + itineraries produced`.
- **AC-23** — Given no offers, when the result is produced, then it has no itineraries and all counters are zero.

**Model invariants** — malformed values are rejected at construction with a domain validation error.
- **AC-24 (error)** — Airport codes must be 3 letters A–Z; airline codes 2 characters A–Z/0–9; flight numbers an airline
  code plus 1–4 digits; provider ids non-blank; money non-negative, EUR only; a segment's origin must differ from its
  destination; an itinerary needs at least one segment; an offer's base price must be greater than zero. Lower-case
  input is normalised to upper case; surrounding spaces are trimmed.

## API contract

None (pure domain). Entry point for later specs: an aggregation that accepts offers one by one and then produces a
result (aggregated itineraries + statistics).

## Domain model impact

Feature package `com.andresps.flights.aggregation.domain`, sub-packages `model`, `validation`, `pricing`,
`deduplication`:

| Type | Invariants / responsibility |
|---|---|
| `AirportCode`, `AirlineCode`, `FlightNumber`, `ProviderId` | Validated, normalised identifiers (AC-24) |
| `Money` | `BigDecimal` amount ≥ 0, EUR, scale 2 (`HALF_EVEN` when normalising) |
| `Segment` | Flight number, origin ≠ destination, departure/arrival `Instant`s (their order is checked by a rule) |
| `Itinerary` | Non-empty ordered list of segments; derives its `ItineraryKey` and canonical representation |
| `ItineraryKey` | Deterministic value derived from airline, flight numbers, airports and departure instants |
| `FlightOffer` | Itinerary + provider + base price (> 0) |
| `AggregatedItinerary` | Itinerary, key, selected offer, base price, final price, providers that offered it |
| `DiscardReason` | Enum of the reasons in AC-13 – AC-19 |
| `AggregationStatistics`, `AggregationResult` | Output of an aggregation |

## Non-functional requirements

- Framework-free (enforced by `ArchitectureTest`); immutable value objects; no `double` for money.
- Deterministic: same set of offers ⇒ same result, independent of acceptance order.
- Volume: offers are accepted incrementally (no need to hold all offers in memory). Memory is O(distinct valid
  itineraries), not O(offers). Results of disjoint key partitions must be combinable later (statistics are additive).

## Assumptions and notes for later specs

- Segment times are `Instant`s; provider adapters convert local times with their offsets.
- A base price must be > 0: a free or negative fare is malformed input, not a sellable offer.
- Prices are normalised to 2 decimals with `HALF_EVEN` on creation; prices that differ only beyond the 2nd decimal
  tie and fall to the tie-break (AC-8).
- **Malformed records never reach the aggregator** (they fail at construction, AC-24). The ingestion report (later
  spec) must count them per provider, so that provider totals reconcile with this spec's statistics.
- "Already departed" is evaluated at aggregation time. Data is up to one ingestion interval old (ADR-0006), so the
  **search spec must also exclude departed flights at query time**.
- The result's list of itineraries has no guaranteed order; search sorts by price.

## Open questions

All resolved (2026-11-05):

- [x] Q1 Times as `Instant` — yes.
- [x] Q2 Same provider and price — earliest arrival, then canonical representation (AC-7).
- [x] Q3 Zero base price — not allowed; `FlightOffer` invariant (AC-24).
- [x] Q4 One reason per discard, explicit order — yes (AC-20).
- [x] Q5 Already departed = first departure not strictly after `now`, no lead time — yes; search filters at query time.
- [x] Q6 More than 2 decimals — rounded `HALF_EVEN`; sub-cent differences tie (AC-8).
- [x] Q7 No output order — yes.
