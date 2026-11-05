# SOLUTION

Write-up required by the [assessment statement](docs/exercise.md) (section 4). It summarises the decisions and
links to the ADRs, which hold the full reasoning.

> **Status: skeleton.** This document is completed as features are implemented. Sections marked *To be completed*
> will be filled with measured results, not plans.

## 1. Architectural decisions and AI tools used

### Architecture

| Decision | ADR |
|---|---|
| Hexagonal architecture organised by feature, framework-free domain and use cases | [ADR-0002](docs/adr/0002-hexagonal-architecture.md) |
| Spec-driven development: every change starts from a spec with Given/When/Then criteria | [ADR-0003](docs/adr/0003-spec-driven-development.md) |
| Automated quality gates (format, tests, ArchUnit, coverage, CodeQL) | [ADR-0004](docs/adr/0004-quality-gates.md) |
| Scheduled pre-ingestion + `POST /api/v1/ingestions` (202, 409 if running) + status endpoint; search never calls providers | [ADR-0006](docs/adr/0006-scheduled-pre-ingestion.md) |
| PostgreSQL (Flyway, `JdbcClient` batching, no JPA) as source of truth, Redis as search cache | [ADR-0007](docs/adr/0007-postgresql-and-redis.md) |
| Minimal React + TypeScript + Vite frontend served by nginx | [ADR-0009](docs/adr/0009-react-frontend.md) |
| Domain assumptions: invalid connections, EUR, one airline, 5% `HALF_EVEN` markup, date filter | [ADR-0010](docs/adr/0010-domain-assumptions.md) |
| Incremental deduplication keyed by a deterministic itinerary key, total-order tie-break | [ADR-0011](docs/adr/0011-incremental-deduplication.md) |

### Core domain (spec 001)

The aggregation rules live in `com.andresps.flights.aggregation.domain`, plain Java with no framework dependency
(enforced by ArchUnit). The domain has four packages:

| Package | Contents |
|---|---|
| `model` | Value objects with invariants: `AirportCode`, `AirlineCode`, `FlightNumber`, `ProviderId`, `Money`, `Segment`, `Itinerary`, `ItineraryKey`, `FlightOffer` |
| `validation` | `ItineraryRule` (one method) and one class per rule, composed in a fixed order by `ItineraryValidator` |
| `pricing` | `MarkupPolicy` and `PercentageMarkupPolicy` (5% by default) |
| `deduplication` | `OfferAggregator` (incremental accumulator), `OfferPreference` (tie-break), result and statistics records |

There are two kinds of bad input, and they are handled differently:

- **Malformed data** (a 2-letter airport code, a negative or zero price, a non-EUR price) fails when the value object
  is built. It never reaches the aggregator. The ingestion report will count these records per provider.
- **Well-formed but unsellable itineraries** (dead legs, invalid connections) are discarded with a `DiscardReason`
  and counted in the aggregation statistics.

**How SOLID applies here, and where it deliberately doesn't:**

| Principle | Where |
|---|---|
| **SRP** | One class per validation rule. `Money` owns rounding, `MarkupPolicy` owns pricing, and `OfferPreference` owns the tie-break. `OfferAggregator` only orchestrates (validate → select → count) and statistics are a separate record. |
| **OCP** | A new rule is a new `ItineraryRule` added to the validator's list. A new pricing scheme is a new `MarkupPolicy`. Neither change touches `ItineraryValidator` or `OfferAggregator`. One caveat: a new rule also needs a new `DiscardReason` constant. The enum is a deliberate, type-safe trade-off and makes a good statistics key. |
| **LSP** | Interface Javadocs state the contracts. A rule returns empty or exactly one reason, never throws for a well-formed itinerary and has no side effects. A markup policy returns EUR at scale 2, never below the base price. So any rule or policy can replace another (tests use lambdas). |
| **ISP** | `ItineraryRule.check` and `MarkupPolicy.apply` each have a single method. The aggregator exposes only `accept` and `result`. |
| **DIP** | `OfferAggregator` depends on the `MarkupPolicy` abstraction and on rules through their interface. Time comes from an injected `java.time.Clock`, so "already departed" is testable. |
| **Not abstracted** | `ItineraryValidator`, `OfferAggregator`, key derivation and the tie-break are concrete classes. Each has one obvious implementation and no variation point, so an interface would only add indirection. |

*To be completed:* resilience strategy per provider (timeouts, retries, partial results) and the module/component
diagram as built.

### AI tools used

The code is developed with **Claude Code** under versioned guardrails ([ADR-0005](docs/adr/0005-ai-assisted-development.md)):

- `CLAUDE.md` with project rules (architecture, testing, git workflow, language rule).
- Skills: `spec-feature`, `implement-spec`, `adr`, `pr-description`.
- Agents: `code-reviewer`, `spec-reviewer`, `test-engineer`.
- Hooks: git guard, Spotless after edits, `./mvnw verify` before finishing a turn.

Every prompt and agent run is logged in [docs/ai/](docs/ai/README.md).

## 2. Data modelling and pagination up to 1,000,000 rows

- **Pagination:** keyset pagination on `(price, id)` instead of offset. See [ADR-0008](docs/adr/0008-keyset-pagination.md)
  for the justification: constant cost per page at any depth and stable results while data changes.
- **Storage and cache:** see [ADR-0007](docs/adr/0007-postgresql-and-redis.md).

### Domain model and deduplication

- **Times are `Instant`s.** Connection and layover times are computed on absolute time, so time zones can't skew
  them. Provider adapters convert local times with their offsets.
- **Money** is a `BigDecimal` in EUR, always at scale 2. Inputs with more decimals are rounded `HALF_EVEN` once, when
  created, so prices that differ only below one cent tie. The markup is computed exactly and rounded once, which
  avoids double rounding.
- **Deduplication key:** a readable, deterministic `ItineraryKey`, e.g.
  `IB3456:MAD-BCN@2026-11-10T08:00:00Z|IB3457:BCN-LHR@2026-11-10T10:30:00Z`. It is made of flight numbers (which
  carry the airline), airports and departure instants. It will double as the natural unique key in persistence.
- **Selection** uses a total order: lowest base price → provider id → earliest arrivals → canonical itinerary
  representation. The winner never depends on which provider answered first.
- **Memory:** `OfferAggregator` keeps one entry per distinct valid itinerary (the preferred offer and a small sorted
  set of providers), not one per offer, and it accepts offers one at a time from streaming parsers. Each entry is a full object graph,
  estimated (not yet measured) at ~1–1.5 KB for a two-segment itinerary, so **~1–1.5 GB of heap at 1M distinct
  itineraries**, with a higher peak while `result()` builds the output. If that's too much, the input can be hash-partitioned by key into independent aggregators: selection is a minimum and the
  statistics are plain counters, so partition results can be concatenated and their counters added. Other
  options are interning airports, airlines and providers, a lighter provider set, or pushing the "keep cheapest" step
  into a PostgreSQL upsert (ADR-0011). The ingestion spec will measure before choosing.
- **"Now" is read once per run**, when the validator is built, so offers that arrive late in a long run aren't judged
  against a later clock. Otherwise the result would depend on arrival order.
- **Untrusted amounts are bounded** (fewer than 10 integer digits, at most 10 decimals), so input like `1E999999999`
  is rejected cheaply instead of triggering huge `BigDecimal` arithmetic.

*To be completed:* table design and indexes, batch sizes, and load/latency measurements at 100k and 1M rows.

## 3. System limitations and trade-offs

Known from the accepted decisions:

- Search data is up to one ingestion interval stale (15 min by default) — [ADR-0006](docs/adr/0006-scheduled-pre-ingestion.md).
- "No concurrent ingestion runs" is guaranteed for a single instance only — [ADR-0006](docs/adr/0006-scheduled-pre-ingestion.md).
- Keyset pagination has no "jump to page N" and no exact total count — [ADR-0008](docs/adr/0008-keyset-pagination.md).
- EUR only, single-airline itineraries, fixed 45 min / 24 h connection thresholds — [ADR-0010](docs/adr/0010-domain-assumptions.md).
- One-way itineraries only: visiting an airport twice (including a return to the origin) is discarded as
  `REVISITED_AIRPORT` — [spec 001](specs/001-itinerary-aggregation/spec.md).
- An itinerary that breaks several rules is counted under its first failing reason only, so the counts add up.
- "Already departed" is evaluated at ingestion time. Data can be up to one interval old, so search must also filter
  departed flights at query time (search spec).
- `OfferAggregator` is single-threaded and holds the distinct itineraries in memory (ADR-0011). Partitioning is
  designed for but not implemented.
- Flight numbers are compared as given: `IB0123` and `IB123` are different flights until provider adapters agree on a
  format.
- Arrival times aren't part of the identity. If providers disagree on arrival for the same flight, the earliest
  arrival is kept (deterministic, but it may not be the true schedule).

*To be completed:* limitations found during implementation and what would change for production.
