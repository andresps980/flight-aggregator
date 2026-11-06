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
| Provider resilience: Resilience4j core, deadlines, per-provider breakers | [ADR-0014](docs/adr/0014-provider-resilience.md) |
| Atomic partitions through a per-attempt buffer | [ADR-0015](docs/adr/0015-atomic-partition-buffers.md) |
| Aggregate one horizon day at a time, statistics summed | [ADR-0016](docs/adr/0016-day-partitioned-aggregation.md) |
| Each day is an atomic snapshot; a less complete day never wipes a more complete one (kept stale, at most 2 h) | [ADR-0017](docs/adr/0017-day-snapshot-replacement.md) |
| Bulk load with PostgreSQL `COPY` (measured 1.8× faster than batched inserts) | [ADR-0018](docs/adr/0018-bulk-load.md) |
| No concurrent runs across instances: session advisory lock on a dedicated connection | [ADR-0019](docs/adr/0019-ingestion-run-lock.md) |
| Two search indexes, `(final_price, id)` and `(origin, destination, final_price, id)`, chosen by `EXPLAIN` on 1M rows | [ADR-0020](docs/adr/0020-search-indexes.md) |
| Opaque, unsigned keyset cursor `v1|price|id|filter fingerprint` | [ADR-0021](docs/adr/0021-search-cursor.md) |
| Redis cache-aside as a decorator of the query ports, keyed by a transactional data version; optional at runtime | [ADR-0022](docs/adr/0022-search-cache.md) |
| Dashboard "real time" by polling the latest ingestion run (30 s, 3 s while running), never auto-replacing results | [ADR-0023](docs/adr/0023-real-time-by-polling.md) |
| Frontend types generated from the OpenAPI description; Vitest + RTL + MSW; ESLint + Prettier; Node 26; runtime config | [ADR-0024](docs/adr/0024-contract-generated-client-and-frontend-toolchain.md) |

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

The dashboard's demo controls make this visible (spec 006). Below, Mock-Gamma was forced down through gds-mock's admin
API and a run started with *Refresh data*: the run is `PARTIAL`, Gamma `FAILED` on all 7 partitions with its breaker
`OPEN`, Alpha and Beta `COMPLETED`; no day was replaced (0 itineraries stored, every day `KEPT_STALE`, ADR-0017), so
search keeps serving the previous, more complete data with `STALE` badges instead of dropping Gamma's offers.

![Data status after a Mock-Gamma outage: run PARTIAL, mock-gamma FAILED 0/7 with breaker OPEN, demo controls with the
Gamma outage switched on](docs/images/dashboard-outage.png)

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

*Superseded by spec 004:* results now go to PostgreSQL day by day, and the container heap is 768 MB (below).

### Persistence and the ingestion pipeline (spec 004)

PostgreSQL replaces the in-memory result store; runs are triggered by a scheduler and by `POST /api/v1/ingestions`,
never overlap, and are persisted with their report. Diagrams: [architecture.md](docs/architecture.md#persistence-and-the-ingestion-pipeline-spec-004).

| Decision | Where |
|---|---|
| Hexagonal split: `IngestionRunService` (plain Java) owns the run lifecycle through three outbound ports (`IngestionRunLock`, `IngestionRunRepository`, `SnapshotMaintenance`) and an injected `Executor`; `DaySnapshotPublisher` implements the existing `DayAggregationSink` and applies a plain-Java `SnapshotPolicy` over a `DaySnapshotStore` port. The scheduler and the controller are inbound adapters; everything PostgreSQL-specific is in `adapter/out/persistence` (ArchUnit rule). | spec 004 |
| A day is the unit of replacement: one transaction deletes its rows, `COPY`s the new ones and upserts `day_snapshot`. A publication replaces only if it is at least as complete as the stored snapshot (or that one has been stale for 2 h); otherwise the old snapshot is kept and marked stale. | [ADR-0017](docs/adr/0017-day-snapshot-replacement.md) |
| `COPY` text format through PgJDBC, streamed from a 64 KiB buffer. | [ADR-0018](docs/adr/0018-bulk-load.md) |
| Session `pg_try_advisory_lock` held on a dedicated connection for the whole run; checked before each day is published (a lost lock stops the run, `FAILED`); orphaned `RUNNING` rows become `ABORTED` when the next run takes the lock. | [ADR-0019](docs/adr/0019-ingestion-run-lock.md) |
| The run id is chosen by the run service (so `POST` returns it at once) and passed to the ingestion use case with a `PublicationGuard`. | spec 004 |
| `ANALYZE itinerary` after each run's writes, so the planner's statistics follow the snapshots (for the search PR). | spec 004, AC-13 |
| Retention at the start of every run: days before today, runs older than 7 days. | spec 004, AC-12 |
| The in-memory store was removed from the application; an in-memory `DaySnapshotStore` fake remains in the tests. | spec 004, Q5 |

**SOLID, pragmatically.** SRP: the policy decides, the publisher applies, the store executes SQL, the run service
orchestrates the lifecycle. OCP: the bulk-load technique is an `ItineraryBulkLoader` behind the store (the batched
alternative lives in tests for the benchmark). ISP: `DaySnapshotStore` (day writes) and `SnapshotMaintenance`
(retention, statistics) are separate ports, implemented by one adapter. DIP: the use cases own every port. **Not
abstracted:** the scheduler uses Spring's `TaskScheduler` directly (it is an inbound adapter); the report JSON mapping
is one concrete class shared by the persistence adapter and the REST response, so the API shows what is stored.

**Measured at 1,000,000 itineraries** (Apple M1 Max, Docker with 6 CPUs / 16 GB, `postgres:17`):

| | Result |
|---|---|
| Bulk load, `COPY` (1,000,006 rows as 7 days, `BulkLoadBenchmarkIT`) | **10.4 s** first load, **13.8 s** replacement |
| Bulk load, batched `INSERT` + `reWriteBatchedInserts` / without | 18.9 s / 24.5 s; 31.0 s / 34.7 s (the "without" run: the same benchmark with the `reWriteBatchedInserts` line removed from `TestPostgres`) |
| Full run in compose (gds-mock deterministic, 946,230 itineraries stored, 7 days `REPLACED`) | **19.2 s** at `-Xmx512m`, **21.2 s** at `-Xmx768m` (first run and replacement alike) |
| Smallest heap that completes | **512 MB** (container peak 717 MiB); 384 MB runs out of memory |
| Chosen heap | **`-Xmx768m`** (1.5× the floor), container peak ≈ 0.99 GiB, was 1,536 MB with the in-memory store |
| Table size | 558 MB (456 MB rows + 103 MB indexes), 618 bytes per row |
| Default compose stack (100,000, chaos on), AC-25 checked by hand | `postgres`, `gds-mock` and `flight-aggregator` healthy (the aggregator started after the other two, `depends_on: service_healthy`); first scheduled run `COMPLETED` in 17.5 s; 94,543 rows queryable in `itinerary` (7 days, one `day_snapshot` row each); `POST` → `202` + `Location`, a second `POST` → `409` with `runningIngestionId`; after `docker compose down` / `up` the rows and both runs were still there (named volume) |

- The heap is now the ingestion working set only (2 days in flight); the store writes and drops each day.
- With 384 MB the JVM exits on `OutOfMemoryError` and the container restarts (compose restart policy); the run it
  left `RUNNING` was marked `ABORTED` by the next run, as AC-15 specifies, observed live during the measurement.
- `./mvnw verify` (both modules, all unit and integration tests, coverage, formatting): **54 s** on the same laptop.
  The flight-aggregator ITs start 4 cached Spring contexts (six IT classes share the recorded-feeds one) and one
  PostgreSQL container for the whole build.

Reproduce: `./mvnw -pl flight-aggregator verify -Pslow-tests -Dit.test=BulkLoadBenchmarkIT` for the load;
`GDS_WORLD_VOLUME=1000000 GDS_MOCK_PROFILE=deterministic AGGREGATOR_JAVA_TOOL_OPTIONS="-Xmx512m" docker compose up
--build -d --wait`, then `curl -X POST localhost:8080/api/v1/ingestions` for the run.

**Found while implementing:**
- A test configuration that replaced the `Clock` bean by *name* was silently overridden by the application's own
  `clock` bean once it was `@Import`ed instead of nested: the run used the real date and asked for the wrong days.
  Test clocks now use a distinct bean name plus `@Primary`.
- `Money` caps every amount at 9 integer digits, the final price included, so `numeric(12,2)` has room to spare; the
  round-trip test first tried a final price the domain cannot produce.
- PostgreSQL stores microseconds: run instants are truncated to microseconds before they are stored, so a run reads
  back equal to what the service returned.
- Closing a `@SpringBootTest` context from inside a test breaks the test framework's context cache, so the
  scheduling IT starts its own application with `SpringApplicationBuilder` and closes it, then checks that no run
  starts or stays `RUNNING` afterwards.
- A shared Testcontainers container returned as a Spring `@ServiceConnection` bean is stopped by Spring when any
  context closes; the shared test container ignores `stop()` (Ryuk removes it when the JVM exits).
- **Shutdown waited for a whole run.** `ThreadPoolTaskScheduler` takes part in the lifecycle stop phase, which runs
  before beans are destroyed and lets running tasks finish; the scheduler's tick waits for its run, so closing the
  application waited for the run to complete, and the executor that should interrupt it was destroyed only after.
  Found by an IT that closes the application mid-run (added after the code review); the schedule is now stopped on
  `ContextClosedEvent`, and the interrupted run is stored `FAILED` with its lock released.
- **The lock connection was shared by concurrent day coordinators** (found in review): it is now used under a lock,
  opened outside the pool, and any failure to confirm the lock counts as losing it.

### Search API and cache (spec 005)

`GET /api/v1/itineraries` (filters `origin`, `destination`, `date`, `maxPrice`, `carrier`; `limit`, `cursor`) and
`GET /api/v1/catalog`, documented by OpenAPI ([docs/api/openapi.json](docs/api/openapi.json), checked by a test) and in
[usage.md](docs/usage.md#search-spec-005). Flow: [architecture.md](docs/architecture.md#search-spec-005).

| Decision | Where |
|---|---|
| New `search` feature. Use cases `ItinerarySearchService` / `CatalogService` (plain Java) validate, check the cursor and issue the next one through two outbound ports (`ItinerarySearchQuery`, `CatalogQuery`); no Java dependency on `ingestion`, both meet only in the schema. | spec 005 |
| Date semantics: no `date` = every future departure; with `date` = that UTC day. Departed itineraries (first departure not strictly after now) are filtered at query time, and also dropped from cached pages. | ADR-0010 (amended), AC-3, AC-4, AC-20 |
| Keyset on `(final_price, id)`, `limit + 1` rows to know whether more follow; the cursor carries a filter fingerprint, so a cursor reused with other filters is a `400`, not a silently wrong page. | ADR-0008, ADR-0021 |
| Cache-aside in Redis as **decorators of the query ports**: the use case cannot tell. Keys = data version + normalized filters + limit + position. The version is a single row bumped as the last statement of every ingestion transaction that changes the read model, so invalidation is atomic with the commit and needs no deletion; TTL 10 min as a safety net. | ADR-0022 |
| Redis is optional: 100 ms timeouts, failures read as misses, a Resilience4j breaker skips Redis for 30 s after repeated failures (WARN once), `search.cache` metric. Readiness doesn't depend on Redis. | AC-21 |
| Codes compared as `bpchar` placeholders, so the `char(n)` columns are not cast and the indexes stay usable. | `ItinerarySearchSql` |

**SOLID, pragmatically.** SRP: validation and cursor rules in the domain (`SearchCriteria`, `Cursor`, `PageSize`), paging
in the use case, SQL in the adapter, caching in a decorator, failure handling in `ResilientCacheStore`. OCP: the cache
was added without touching the use case or the SQL adapter; a different cache is another `CacheStore`. LSP: the
decorators honour the port contract (same items and next position; fewer items only for departed flights, as the
port's Javadoc allows). ISP: `DataVersionQuery` is its own port, used only by the decorators. DIP: the application owns
every port. **Not abstracted:** the cache codec serialises the search records directly (a cache-only DTO layer would
duplicate them; the key prefix `v1` is bumped if the format changes), and the controllers take raw strings so the use
case owns every validation message.

**Measured at 1M rows** (`SearchPerformanceIT`, Apple M1 Max, Docker 6 CPUs / 16 GB; full report with every plan:
[docs/measurements/search-1m.md](docs/measurements/search-1m.md)):

| | Result |
|---|---|
| Plans | Every AC-22 combination is an index scan in keyset order stopped by `LIMIT`; no sequential scan, no sort (ADR-0020) |
| `COPY` 1M rows without / with the two search indexes | 12.1 s / 17.9 s first load; 32.6 s replacement with them |
| Search, server side, first page | 0.2–2.9 ms for every AC-22 combination (each with and without `maxPrice`); 49 ms median for origin + carrier + low `maxPrice` (two filters outside the route index) |
| API, client side, uncached / cached (median) | 2.6–8 ms / 1.8–5.4 ms for typical searches; 56 ms / 3.0 ms for that two-filter case |
| Catalog, uncached / cached (median) | 420 ms (grouped scan of 1M rows, once per data version) / 1.4 ms |

The cache saves little on a typical search, which is already sub-millisecond in PostgreSQL thanks to the indexes;
it pays off for the expensive requests — the catalog (≈ 300×) and the filter combinations no index serves (≈ 18×) —
and it takes repeated load off the database.

Default compose stack, AC-25 checked by hand: `postgres`, `redis`, `gds-mock` and `flight-aggregator` healthy; after the
first scheduled run (94,543 rows) a search returns data and the repeated request is a cache hit. `./mvnw verify` (both
modules): **66 s**.

**Changed after the code review:** the catalog is memoised per data version in the JVM (single-flight), so a version
bump or a Redis outage costs one scan instead of one per concurrent request; a reused catalog drops past days; the
breaker records `Error`s too; the cache format is pinned by a golden test.

**Found while implementing:**
- `RestClient` expands and encodes the URI template, so a test that wrote `%20` sent `%2520`; the test now writes
  plain spaces.
- With Redis paused, the `SET`s that timed out had already been written to the socket; Redis ran them when it resumed.
  Harmless (the value is what PostgreSQL returned), but the recovery test first expected the pre-outage page.

### Dashboard (spec 006)

`frontend/`: React 19 + TypeScript + Vite, no state-management, UI, chart or date library (runtime dependencies:
`react`, `react-dom`), built into static files served by an unprivileged nginx that also proxies `/api` to the
aggregator. Walkthrough for backend developers: [docs/frontend.md](docs/frontend.md).

| Decision | Where |
|---|---|
| Search on submit, filters in the URL (shareable, back/forward), destinations filtered by the catalog's routes, dates with freshness | spec 006 AC-1 – AC-6 |
| Keyset "Load more"; short or empty pages while `hasMore` are followed automatically (≤ 5 empty in a row); no total | AC-12 – AC-14, ADR-0008 |
| Times shown in UTC, labelled: the API has no airport time zones | AC-8, Q1 |
| Polling instead of SSE/WebSocket; "New data available" only for runs that published data, results never replaced automatically | ADR-0023, AC-15 – AC-18 |
| Types generated from `docs/api/openapi.json`; the chain Java → JSON → TypeScript is checked at both links | ADR-0024, AC-27 |
| Demo controls behind a runtime flag (`DEMO_CONTROLS`, on in compose) written by the container's entrypoint | AC-22 |
| Plain-SVG charts of the loaded results, one validated hue, a table alternative and tooltips | AC-19 |

**Backend follow-up.** The ingestion endpoints are now in the OpenAPI description (`202` + `Location`, `409` with
`runningIngestionId`, `200`/`400`/`404`), and a customizer makes the schemas exact: response properties required
(nullable where they can be null), `ProblemDetail` flat, nullable references as `anyOf [$ref, null]`. The last one was
**found by the generated types**: springdoc wrote `$ref` next to `type: null`, so the TypeScript said a run's report
was never null. An ArchUnit rule keeps response records from omitting nulls, which would make "required" untrue.

**How the frontend maps to the backend's principles.** Pure modules (`format`, `filters`, `charts`, `newData`) are the
core and are unit-tested alone; `api/client.ts` is the only outbound adapter; hooks orchestrate and hold state;
components only render. SRP: one component or hook per concern. DIP-like: components never call `fetch`. Not
abstracted: no repository/service layers or dependency injection — at this size they would be ceremony.

**Measured.** `npm run check` (types regenerated and diffed, Prettier, ESLint, `tsc`, 118 tests with coverage):
**7 s**; coverage 99.7 % lines / 95.5 % branches (gate 80 / 70). Production bundle 243 kB JS (**76 kB gzipped**),
5.5 kB CSS. Image smoke test (22 checks): 10 s. `./mvnw verify` (both modules): 65 s.

Default compose stack, AC-25/AC-30 checked by hand: five services healthy, dashboard at <http://localhost:3000>,
searches return data after the first run, *Refresh data* → `202` then `409`, nginx finds the aggregator again after a
restart (`502` meanwhile). The resilience demo through the dashboard's proxy: Mock-Beta forced down, manual run →
`PARTIAL`, Beta `FAILED 0/7` with its breaker `OPEN`, every day `KEPT_STALE`, results `STALE`.

**Found while implementing:**
- msw 3 renamed `onUnhandledRequest` to `onUnhandledFrame`; the old name was silently ignored, so an unexpected request
  only warned. Found by `tsc`, not by a failing test.
- Prettier inherited the repository's 4-space `.editorconfig`; the frontend now pins 2 spaces.
- The React hooks lint rule against setting state inside effects pushed the design to start searches from events
  (load, submit, back/forward, reload) instead of "filters changed" effects — simpler to follow.
- The compose healthcheck used `localhost`, which resolves to `::1` in the container while nginx listens on IPv4:
  the dashboard worked but the service stayed unhealthy. Now `127.0.0.1`, and the smoke test runs that command.
- **A layout bug found by hand** (the author, in Brave at 1170 px): the results column painted over the search form
  and the status table. Grid/flex children cannot shrink below their widest content (the providers table, `<select>`s
  as wide as their longest option) unless told to (`minmax(0, 1fr)`, `min-width: 0`). Fixed with named grid areas
  (one column below 960 px, status and demo under the results), contained tables, wrapping and clipped SVGs, and
  guarded by a **Playwright layout check in Chromium** at four widths, which also found a second bug: screen-reader
  tables hidden with `overflow: hidden` (which does not apply to tables) widened the page at 768 px. The check fails on
  the old layout and on a deliberately broken one.
- Versions: TypeScript 5.9 and ESLint 9, the newest every tool's declared peer range accepts
  (`openapi-typescript`, `typescript-eslint`, `eslint-plugin-jsx-a11y`), instead of forcing peers.

**Changed after the reviews:** a superseded poll could flag "status unavailable", and a poll in flight during *Refresh
data* could replace the new `RUNNING` run with the previous one (both reproduced by tests on the old code, then
fixed); keyboard focus was lost after every search (the form remounted); a non-numeric max price was silently dropped
by `<input type="number">`; repeated items across pages are de-duplicated; upstream URLs are validated before they
reach the nginx config; the OpenAPI → types check runs locally too.

### AI tools used

The code is developed with **Claude Code** under versioned guardrails ([ADR-0005](docs/adr/0005-ai-assisted-development.md)):

- `CLAUDE.md` with project rules (architecture, testing, git workflow, language rule).
- Skills: `spec-feature`, `implement-spec`, `adr`, `pr-description`.
- Agents: `code-reviewer`, `spec-reviewer`, `test-engineer`.
- Hooks: git guard, Spotless / Prettier after edits, `./mvnw verify` and/or the frontend checks before finishing a
  turn, for whatever changed.

**The guardrails also constrain the agent itself.** In PR 8 the agent tried to register the new Prettier hook and
`npm` permissions in `.claude/settings.json`; Claude Code's auto-mode classifier blocked the edit as
*self-modification* (an agent widening its own permissions or hooks). The agent wrote the hook script, explained the
exact change, and the author applied it by hand and committed it. Changes to the agent's own rules go through a human
([docs/ai/008](docs/ai/008-dashboard.md)).

Every prompt and agent run is logged in [docs/ai/](docs/ai/README.md).

PRs are merged with **merge commits, not squash**, to keep the spec-driven history visible on `main`: each feature
branch has a `docs:` commit with the spec and ADRs, a `test:` commit with the tests written first, then the
implementation and review fixes. The test-first commits are **intentionally red** (the tests don't compile or fail
until the implementation lands); every merge result is green.

## 2. Data modelling and pagination up to 1,000,000 rows

- **Pagination:** keyset pagination on `(final_price, id)` instead of offset ([ADR-0008](docs/adr/0008-keyset-pagination.md)),
  measured at 1M rows below (*Keyset vs offset, measured*).
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

### Read model in PostgreSQL (spec 004)

One **denormalized row per aggregated itinerary** in `itinerary` (columns in [architecture.md](docs/architecture.md#schema)):

- **Every search filter and the sort key are scalar columns of the same row**: `origin`, `destination`, `airline`,
  `final_price` (what users filter and sort on), `departure_date`/`departure_at`. A composite B-tree such as
  `(origin, destination, final_price, id)` can then answer a filtered, price-ordered page with one index range scan
  and `LIMIT`, with no join. The search spec adds and measures those indexes (each one slows the bulk load).
- **`id bigint` identity** is the keyset tie-breaker with `final_price` (ADR-0008). Ids change when a day is replaced,
  so a cursor taken before a replacement continues by position and may skip or repeat a result; the search spec
  documents it.
- **Segments as JSONB**, read as is: they are only displayed, so a `segment` table (2–3× the rows) and its join on
  every read buy nothing.
- **`itinerary_key` unique** (spec 001's key, which includes departure instants) guards against duplicates;
  **`departure_date`** (UTC date of the first departure, i.e. the provider partition) is the unit of snapshot
  replacement and retention: one indexed `DELETE` each.
- **Prices `numeric(12,2)`**, EUR (ADR-0010); providers as `text[]` (sorted), the run id and timestamps for audit.
- Day status in `day_snapshot` (complete or not, missing providers, stale since), runs in `ingestion_run` (report as
  JSONB), both queryable by the dashboard.
- Size measured at 1M: **618 bytes per row, 558 MB** with today's indexes; loaded in 10–14 s with `COPY` (above).
- Partitioning by `departure_date` was considered and deferred (ADR-0017): it would make retention instant and avoid
  dead tuples, at the cost of DDL on every run; at ≈ 1M rows plain deletes and autovacuum are enough.

### Keyset vs offset, measured (spec 005)

Same filters, page of 20, server side, median / p95 of 30 runs on 1,000,006 rows
([full table](docs/measurements/search-1m.md)):

| Filters (matching rows) | Page | Keyset | `OFFSET` |
|---|---:|---:|---:|
| none (1,000,006) | 1 | 0.75 / 1.24 ms | 0.61 / 0.76 ms |
| none | 1,000 | **0.55 / 0.75 ms** | **50.30 / 68.81 ms** |
| `date` (142,858) | 1,000 | 0.47 / 0.63 ms | 63.68 / 65.51 ms |
| `carrier` (42,082) | 1,000 | 0.76 / 0.94 ms | 159.11 / 176.64 ms |
| `origin` (29,375) | 1,000 | 0.89 / 1.78 ms | 94.91 / 104.69 ms |
| `carrier` + `maxPrice` (12,397) | 620 (last) | 0.99 / 1.22 ms | 174.92 / 192.35 ms |
| busiest route, no date, `maxPrice` above all (993) | 50 (last) | 0.53 / 0.91 ms | 2.14 / 2.98 ms |

- **Keyset cost does not depend on depth**: page 1,000 costs what page 1 costs, because the query seeks the cursor
  position in the index and reads `limit + 1` entries.
- **`OFFSET` grows linearly**: page 1,000 reads and discards 19,980 rows first (≈ 90× slower here), and with a
  residual filter (carrier) every discarded row is also a heap fetch (≈ 200×). At the last page of the unfiltered
  1M result it would read the whole index.
- **Offset is not even correct here**: an ingestion run replaces whole days every 15 min, so a row inserted or deleted
  before the current position shifts every later page (duplicates or gaps), whereas keyset continues from a position.
- The price of keyset: no "jump to page N" and no total count (an exact `COUNT(*)` would read up to 1M rows per
  request); the dashboard pages forward with "more results".

## 3. System limitations and trade-offs

Known from the accepted decisions:

- Search data is up to one ingestion interval stale (15 min by default) — [ADR-0006](docs/adr/0006-scheduled-pre-ingestion.md).
  A day missing a provider keeps its previous snapshot for up to 2 h (ADR-0017).
- Keyset pagination has no "jump to page N" and no exact total count — [ADR-0008](docs/adr/0008-keyset-pagination.md).
- EUR only, single-airline itineraries, fixed 45 min / 24 h connection thresholds — [ADR-0010](docs/adr/0010-domain-assumptions.md).
- One-way itineraries only: visiting an airport twice (including a return to the origin) is discarded as
  `REVISITED_AIRPORT` — [spec 001](specs/001-itinerary-aggregation/spec.md).
- An itinerary that breaks several rules is counted under its first failing reason only, so the counts add up.
- "Already departed" is evaluated at ingestion time and again at query time by search (spec 005), including on cached
  pages.
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
Persistence and pipeline (spec 004):

- **Partial days favour completeness over freshness.** While a day is stale (a provider missing), the contributing
  providers' fresh prices for that day are ignored, for at most `max-stale-age` (2 h). A per-provider merge would be
  fresher but cannot be exact, because rows keep only the selected offer (ADR-0017).
- **Dead tuples.** Every run replaces up to 7 days, so `itinerary` accumulates dead rows until autovacuum catches up:
  after two 1M replacements in a row there were 1.9 M dead tuples next to 0.95 M live ones. That costs disk and some
  scan efficiency, not correctness. Production would tune autovacuum for this table or move to day partitions
  (ADR-0017).
- **One bad row fails its day.** `COPY` has no per-row errors; the day is `WRITE_FAILED` and keeps its previous
  snapshot.
- **The run lock is a PostgreSQL session lock.** It needs a direct connection or a session-mode pooler (not PgBouncer
  in transaction mode) and holds one extra (non-pooled) connection for up to 10 min. A lost lock is detected at the
  next day boundary, so a run whose session died while writing a day can still commit that day after another
  instance has started. Each day stays atomic and two writers of the same day are serialised by PostgreSQL (the
  second one's rows conflict on the unique key or replace the first's whole day), so readers never see a mix; there
  is no fencing token in the write itself — ADR-0019.
- **Pool size vs. concurrent days.** Each day in flight writes on its own pooled connection; the default pool (10)
  covers the default 2 days with room for the API. Raising `max-concurrent-days` well beyond the pool size makes
  publishes wait for connections (and fail as `WRITE_FAILED` after Hikari's 30 s timeout); keep
  `spring.datasource.hikari.maximum-pool-size` ≥ `max-concurrent-days` + 2.
- **The ingestion endpoints are unauthenticated**, like the rest of the API for now; anyone who can reach the service
  can start a run (at most one at a time).
- **Runs started by `POST` are not cancellable**, and the history keeps only the last 7 days.
- **A run cut short by a crash** stays `RUNNING` until the next run marks it `ABORTED`; with the default schedule that
  is at most 15 min (plus the restart).
- **Graceful shutdown** interrupts a run in progress and waits up to 30 s for it to store its `FAILED` state (compose
  gives the container 40 s before killing it); a run killed harder stays `RUNNING` until the next run aborts it.
- **Failure messages are generic** (`Unexpected error (IllegalStateException)…`): the API never serves exception
  text, which may contain SQL or host names; the details are in the log under the run id.

Search and cache (spec 005):

- **Pagination across a refresh.** A day's replacement gives its rows new ids. A cursor taken before continues from
  its `(price, id)` position in the new data, so an itinerary at the boundary may be skipped or repeated and prices
  may change between pages. Accepted: every page is consistent, it only affects a walk that spans a refresh (every
  15 min, seconds of writes), and rejecting old cursors would send users back to page 1 after every run.
- **No total count, no page N, no backwards cursor**, and only one sort order (price). Another order needs its own
  index and cursor version.
- **Cached pages can be short**: items that departed since the page was cached are dropped, so a page can have fewer
  than `limit` items, even none, while `hasMore` is true. The contract tells clients to keep paging.
- **Every run invalidates the whole cache**, also for days that did not change; a per-day version would keep more hits
  at the cost of keys that span several days. Every request, hit or miss, reads the data version from PostgreSQL, so
  PostgreSQL must be up even for cache hits.
- **The catalog is computed by a grouped scan of the future rows** (≈ 420 ms at 1M rows; no index helps a `DISTINCT`
  over every route). It runs once per data version per instance — memoised in the JVM behind a lock, also when Redis
  is down — but a run bumps the version once per day it writes, so a run triggers up to 7 scans. A cached catalog
  can list a route whose last flight has just departed until the next run; past days are dropped.
- **Cache keys are client-controlled** (any position or `maxPrice` is a new key): a client looping over crafted
  values gets misses and fills Redis, bounded by `maxmemory 256mb` + LRU. Signed cursors or caching only first pages
  would close it if needed.
- **Until the breaker opens**, a request with Redis hung can wait for two 100 ms timeouts (read and write); after 5
  failed calls Redis is skipped for 30 s.
- **`/actuator/metrics` is exposed on the application port without authentication**, like the API; production would
  move management to its own port or behind the network policy.
- **Cursors are not signed**: a client can craft one to start anywhere; the data is public, so nothing is gained.
- **Selective filters outside the indexes** (e.g. a rare carrier on its own) scan more of the price index; measured
  combinations are in ADR-0020.
- **Search is unauthenticated and not rate limited**, like the rest of the API.

Dashboard (spec 006):

- **Times are UTC only**: the API exposes no airport time zones; local times would need a zone per airport in the
  catalog.
- **Polling, not push**: up to 30 s before a scheduled run is noticed, and every open tab polls (one primary-key read
  every 30 s, every 3 s during a run). SSE is the upgrade path (ADR-0023).
- **Charts describe the loaded results**, not the whole result set (there is no total by design).
- **Demo controls and Refresh are unauthenticated and not CSRF-protected**: any page the user visits could POST to
  `localhost:3000/api/v1/ingestions` or toggle a gds-mock outage. Acceptable for a local demo stack (gds-mock's admin
  API is published unauthenticated on 8081 anyway); production would put authentication in front, disable
  `DEMO_CONTROLS`, and check `Origin` on state-changing requests.
- **No browser end-to-end flows**: behaviour is tested in jsdom with MSW, layout in headless Chromium with a mocked
  API (one engine, four widths), the image with a smoke test, and the stack by hand.
- **`npm audit`: 7 moderate advisories** in `minimatch` inside the dev-only lint toolchain (`typescript-eslint`); they
  concern glob patterns we write ourselves. Production dependencies audit clean. Accepted until upstream updates.
- **Pinned behind the latest majors**: TypeScript 5.9 and ESLint 9 until the tools support TypeScript 7 / ESLint 10.

*To be completed:* what would change for production.
