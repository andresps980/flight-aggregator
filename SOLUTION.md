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
| One `gds-mock` app serving the three providers, chaos per provider, no dependency on the aggregator | [ADR-0012](docs/adr/0012-single-gds-mock-application.md) |
| Mock data generated lazily per request from per-itinerary seeds, streamed, never materialised | [ADR-0013](docs/adr/0013-stateless-seeded-streamed-generation.md) |

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

### Mock GDS providers (spec 002)

`gds-mock` is the test double of the three external providers: a separate Spring Boot app that shares no code with
the aggregator (an ArchUnit rule forbids any dependency on `com.andresps.flights`). It is built to exercise the
aggregator, not to look pretty: cross- and same-provider duplicates at different prices, one invalid itinerary
kind per spec 001 discard reason, malformed records, three different time representations, latency, random `500`s and
a runtime outage switch. Formats: [docs/providers.md](docs/providers.md).

| Concern | Design |
|---|---|
| Data | `WorldGenerator` (plain Java). Itinerary *i* of day *d* comes from its own `SplittableRandom(seed, d, i)`, so a provider or a day is generated on its own and every provider sees the same itinerary. Index ↔ (airline, first flight number) makes identities unique by construction. |
| Formats | One `ProviderFormat` per provider (Jackson 3 streaming generator, StAX, `BufferedWriter`). A new provider is a new class; a shared contract test checks round trip, empty feed, streaming and client disconnects for every format. |
| Chaos | `ChaosPolicy` (plain Java, injected randomness) decides; `ChaosInterceptor` applies it before the controller. Serializers and controllers know nothing about chaos (ArchUnit). |
| Reconciliation | `GET /admin/world/stats` reports what the world contains and what spec 001 should produce from the well-formed records, so the ingestion PR can check its counts exactly. |

**SOLID, pragmatically.** SRP: generation (`ScheduleFactory`, `OfferFactory`), serialization (one class per format),
chaos (policy vs. interceptor) and statistics are separate. OCP: a provider is added with a new `ProviderFormat`
bean, and nothing else changes. LSP: every format honours the same contract, checked by one abstract test that runs
for each format. ISP/DIP: controllers depend on `ProviderFormat` and the injected `Sleeper` / `RandomGenerator`
supplier make chaos testable without sleeping. **Not abstracted:** the module has no `application/port` layer. The
mock has no use-case orchestration and no driven ports, so ports would be single-implementation ceremony.

**Measured at 1,000,000 itineraries** (Docker on an Apple Silicon laptop, `deterministic` profile, `-Xmx256m`):

| | Mock-Alpha | Mock-Beta | Mock-Gamma |
|---|---:|---:|---:|
| Records | 619,577 | 619,925 | 619,224 |
| Feed size | 144 MB | 332 MB | 100 MB |
| Full feed, one request | 2.7 s | 10.6 s | 2.8 s |

The container stayed at about 229 MiB with the 256 MB heap cap, and nothing was logged at WARN or ERROR. The three
feeds requested in parallel took 11.4 s, about as long as the slowest one alone. `GET /admin/world/stats` took 1.7 s
the first time. Beta is the slowest (verbose XML through StAX); a per-day partition is about 1/7 of these numbers.

**Found while implementing:** the first version applied the latency **twice** per feed. A streamed response is
asynchronous, and Spring dispatches the request again when the body completes, so the interceptor ran a second time.
That also made it possible to inject a `500` after a feed had already been sent. `ConcurrencyIT` (three providers in
parallel, expected ≈ slowest, got ≈ sum) exposed it; the interceptor now ignores `ASYNC` dispatches.

### Provider ingestion and resiliency (spec 003)

The `ingestion` feature fetches every (provider, day) partition of the horizon, aggregates **one day at a time** and
produces a report. The flow diagram is in [architecture.md](docs/architecture.md#ingestion-flow-spec-003).

| Decision | Where |
|---|---|
| Use case `IngestionService` (plain Java) depends on two outbound ports: `ProviderFeed` (one per provider) and `DayAggregationSink`. A new provider is a new `FeedParser` + `aggregator.providers.<id>.base-url`; the use case doesn't change. | [spec 003](specs/003-provider-ingestion/spec.md) |
| Streaming parsers: Jackson 3 token stream, StAX cursor (DTDs refused, so XXE-safe), a line reader. Records are parsed one at a time into the attempt's buffer, never the whole document. Hostile input is bounded: 64 KiB per string, text or line, 16 legs or segments per record, `max-records-per-partition` (500,000) per attempt. | adapters `alpha`, `beta`, `gamma` |
| Resilience lives in infrastructure: read timeout 6 s, attempt deadline 60 s, Resilience4j circuit breaker per provider (≥ 80 % of the last 10 attempts, 30 s open), retry 3× with 500 ms / 1 s ± 50 % backoff; run deadline 10 min. | [ADR-0014](docs/adr/0014-provider-resilience.md) |
| Partition atomicity: each attempt parses into its own buffer; only a completed attempt's buffer reaches the aggregator. | [ADR-0015](docs/adr/0015-atomic-partition-buffers.md) |
| One `OfferAggregator` per day, fed by a single consumer (the day's coordinator), statistics summed. Valid because the identity includes departure instants and providers partition by UTC date of first departure. | [ADR-0016](docs/adr/0016-day-partitioned-aggregation.md) |

**Why these resilience values.** The mock's latency is 100–5000 ms and 10 % of requests fail.
- A read timeout below 5 s would turn about 40 % of normal answers into retries, so it is 6 s; ingestion runs in the
  background and can afford to wait.
- With 3 attempts a partition is lost with p ≈ 0.1 % instead of 10 %.
- The breaker must not open on that noise: 8 failures in 10 attempts at 10 % has p ≈ 4 × 10⁻⁷. A seeded test with
  10,000 calls checks it stays closed, and another that an outage opens it after 10 attempts (≈ 3 s, since a `503`
  is immediate).
- The 30 s open wait covers the rest of the run that detected the outage but is far below the 15 min interval, so the
  next run always probes again (half-open). It is tested with the real 30 s on a controllable clock.

**Partial results.** A failed partition never fails the run: a provider is `COMPLETED`, `PARTIAL` or `FAILED` by its
partitions, and the run is `FAILED` only if every provider failed (no exception either way). A day is published if at
least one provider delivered it, naming the missing providers, so the persistence spec can keep their previous data.

**Reconciliation.** With no failures, every figure of the report equals gds-mock's `/admin/world/stats`, for the run
and for each day: records and malformed records per reason per provider, offers received, discards per reason,
duplicates merged and itineraries produced. The build checks it against recorded feeds (`IngestionIT`). The `e2e`
test checks it against a running mock; it was run by hand for this PR at 100,000 and 1,000,000 itineraries, and the
CI smoke job will run it in a later PR.

**Found while implementing:**
- The attempt deadline first interrupted the *calling* thread. That only aborts a blocked socket read on a virtual
  thread, and `HttpProviderFeedIT` calls `fetch` from a platform thread, so a trickling body ran to completion. Each
  attempt now runs on its own virtual thread, and the caller waits for it with the deadline. That works whatever
  thread calls `fetch`.
- A partition still waiting for its provider's permit could start a request just after the run deadline, as soon as
  the cancelled request released the permit. It now gives up instead.
- An `OutOfMemoryError` was wrapped as an ordinary failure. Simply rethrowing it would have left the day's
  coordinator waiting for the run deadline. Now every partition always delivers an outcome (`UNEXPECTED_ERROR`) and
  the `Error` is rethrown.
- The cross-zone example in `docs/providers.md` said OPO 12:55 Lisbon → BOS 12:15 New York is a 6 h 20 min flight.
  On 6 November it is 4 h 20 min, because the US left DST on 1 November. The parser test caught it.

**Measured at 1,000,000 itineraries.** gds-mock in the `deterministic` profile, 1,840,053 offers → 946,092
itineraries, G1, Apple Silicon laptop. The table shows the smallest heap (`-Xmx`) with which a run completes. The
first four rows use a sink that discards each day, to isolate the ingestion's own working set; the last row uses the
real in-memory sink, which keeps every published day.

| Sink | Days in flight | Heap that completes | Run | With less heap |
|---|---:|---:|---:|---|
| discarding | 1 | 256 MB | 11.4 s | — |
| discarding | **2 (default)** | **512 MB** | 8.1 s | 384 MB: `PARTIAL` (GC pauses cause read timeouts); 256 MB: out of memory |
| discarding | 3 | 768 MB | 7.4 s | 512 MB: `PARTIAL` |
| discarding | 7 | 1 GB | 8.2 s | 768 MB: out of memory |
| in-memory (whole world) | 2 | 1.5 GB | 7.6 s, reconciles exactly | 1 GB: `PARTIAL` |

- **The working set follows the days in flight, about 250 MB per day at 1M.** That is better than the estimate in
  ADR-0016 (≲ 0.5 GB per day), because buffers are fed and dropped as soon as each partition arrives.
- **The retained world costs ≈ 1–1.2 GB for 946,092 itineraries**, about 1.2 KB each. That confirms ADR-0011's
  estimate, and it is what the PostgreSQL sink will remove.
- **The default stays at 2 days.** In this test (no latency) it is as fast as 3 or 7 days, with half or a quarter of
  their memory. Against the chaos mock (100–5000 ms latency) a run takes about 15 s instead of about 5 s, which is
  negligible against a 15 min interval.
- **The container heap is `-Xmx1536m`** (`flight-aggregator/Dockerfile`, about 2 GB of container memory). That is
  what the in-memory sink needs at 1M. `-XX:+ExitOnOutOfMemoryError` makes the container restart instead of
  continuing in an unknown state.
- **Too little heap degrades gracefully before it fails.** GC pauses turn into timeouts, so the report is `PARTIAL`
  instead of the run crashing.

Reproduce it: start gds-mock with `--gds.world.volume=1000000` (deterministic profile), then run
`./mvnw -pl flight-aggregator verify -Pe2e-tests -Dit.test=IngestionReconciliationE2EIT "-DargLine=-Xmx1536m"`.

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
  set of providers), not one per offer, and it accepts offers one at a time from streaming parsers. Measured in spec
  003: **about 1.2 KB per itinerary**, so the whole 1M world is about 1–1.2 GB. Ingestion doesn't hold the world:
  it runs one aggregator per horizon day (duplicates never cross days, because the identity includes departure
  instants) and sums the additive statistics (ADR-0016). Its working set is about 250 MB per day in flight at 1M,
  512 MB with the default 2 (measurements in section 1). Further options, not needed so far: interning airports,
  airlines and providers, a lighter provider set, or a PostgreSQL upsert for "keep cheapest" (ADR-0011).
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
- `OfferAggregator` is single-threaded and holds a day's distinct itineraries in memory. Ingestion aggregates one day
  per aggregator (ADR-0016), so memory follows the days in flight, not the world (measurements above).
- Day partitioning assumes providers partition by the UTC date of the first departure (true for gds-mock). A provider
  that broke it would make an itinerary served under two days appear twice: a duplicate, never a lost offer.
- Flight numbers are canonical without leading zeros (`IB0123` = `IB123`), whatever the provider sends.
- Arrival times aren't part of the identity. If providers disagree on arrival for the same flight, the earliest
  arrival is kept (deterministic, but it may not be the true schedule).

- gds-mock regenerates the world on every request (each provider call walks every itinerary, including those it
  doesn't offer). That is CPU instead of memory, and cheap at this volume (see the measurements above). Records come
  in generation order, not shuffled.
- gds-mock's admin API is unauthenticated (a mock on an internal network), and the three providers share one JVM, so
  a GC pause or a crash affects all of them at once. Real providers fail more independently.
- gds-mock has no rate limiting: a full 1M feed keeps a CPU core busy for a few seconds (up to the 30 min async
  timeout for a very slow client), so anyone who can reach it can load it. That is acceptable for a mock on an
  internal network, next to the unauthenticated admin API.
- gds-mock has no catch-all exception handler. Unexpected errors use Spring Boot's default error response. A
  catch-all would also capture framework exceptions (unknown path, wrong method) and client disconnects, turning
  them into 500s or ERROR logs.
- gds-mock doesn't simulate failures in the middle of a stream (truncated responses), gzip or slow trickling. Failures
  happen before the first byte only.
- Changing the generator code changes the data for a given seed. Determinism holds per version of the mock.

Ingestion (spec 003):

- The ingestion has no trigger yet (no scheduler, no endpoint) and keeps results in memory. The in-memory sink holds
  every published day, i.e. the whole world, which dominates the heap at 1M (measurements above). The PostgreSQL
  adapter (next spec) writes and drops.
- A partition is buffered as parsed offers until its attempt completes (ADR-0015), and a retry re-downloads the whole
  partition. There is no resume and no disk spooling.
- Breaker state is per JVM instance and lives in memory: a restart closes every breaker, and replicas don't share
  state.
- A `RUN_TIMEOUT` partition reports 0 attempts: the attempts of a cancelled request aren't counted.
- `HttpURLConnection` silently re-sends a GET once when a connection resets before the response. A single reset never
  reaches our retry, and that extra request isn't counted in the report's attempts.
- An attempt aborted by a deadline frees its virtual thread once the socket closes. The caller waits at most one
  read timeout for that (logged at WARN if exceeded), so a run can overrun its deadline by that much at worst.
- Any non-2xx status that isn't 5xx (4xx, but also an unexpected 1xx or an unfollowed 3xx) is a `CLIENT_ERROR`: not
  retried, not a breaker failure.
- Gamma is read as UTF-8: invalid bytes become U+FFFD (so the record is usually malformed), and a byte-order mark
  makes the header check fail the document.
- With too little heap for the days in flight, the run degrades rather than crashes: GC pauses cause read timeouts,
  and the report is `PARTIAL`. With far too little, it runs out of memory; affected partitions are reported as
  `UNEXPECTED_ERROR` and the error is rethrown.
- **Time-zone rules must agree between provider and aggregator.** Mock-Gamma sends local times plus a zone, and
  Mock-Beta sends offsets computed from the provider's rules. If the two sides run different tzdata versions, an
  instant can shift silently. CI found exactly that: tzdata 2026 moved `Africa/Casablanca` from UTC+1 to UTC+0, so
  fixtures recorded on an older JDK no longer reconciled. Two itineraries became invalid connections, and
  cross-provider duplicates stopped matching.
  - Mitigation in this repository: gds-mock only uses zones whose rules are stable across tzdata versions. A
    `CatalogTimeZonesTest` checks this, and the four volatile ones (Casablanca, Mexico City, São Paulo, Istanbul) were
    replaced, so the tests no longer depend on the JVM's tzdata.
  - With real providers: keep JDKs (tzdata) current, prefer offsets or UTC, and detect disagreements by comparing the
    same flight across providers.
- Gamma local times in a DST gap or overlap are resolved by `java.time` defaults (shifted forward in a gap, earlier
  offset in an overlap).
- "No concurrent runs" isn't enforced yet: the use case documents it and the trigger (next spec) will guard it.

*To be completed:* what would change for production.
