# Spec 003: Provider ingestion

- **Status:** Implemented
- **Branch:** feat/003-provider-ingestion
- **Related ADRs:** [ADR-0006](../../docs/adr/0006-scheduled-pre-ingestion.md),
  [ADR-0011](../../docs/adr/0011-incremental-deduplication.md), [ADR-0012](../../docs/adr/0012-single-gds-mock-application.md),
  [ADR-0014](../../docs/adr/0014-provider-resilience.md), [ADR-0015](../../docs/adr/0015-atomic-partition-buffers.md),
  [ADR-0016](../../docs/adr/0016-day-partitioned-aggregation.md)

## Context

From the [statement](../../docs/exercise.md): *"Implement an ingestion layer that queries all 3 mock APIs
simultaneously. Each provider returns data in different legacy structures"* and *"The mock external provider APIs
have artificial latencies (ranging from 100ms to 5000ms) and occasional 500 errors. Your aggregation logic must not
block or fail entirely if one provider drops out."* Volume: 100,000 – 1,000,000 combinations per day.

Spec 001 delivered the framework-free aggregation domain; spec 002 the three mock providers and the
`/admin/world/stats` oracle. This spec connects them: three streaming provider adapters and a parallel, resilient
ingestion use case that aggregates **one horizon day at a time** with spec 001's `OfferAggregator` and produces an
**ingestion report**. Results leave through an outbound port with an in-memory adapter, so the next spec only adds
PostgreSQL, scheduling and the REST endpoints (ADR-0006).

## Definitions

- **Reference date** — today in UTC from the injected `Clock`. **Horizon** — the `horizon-days` UTC days starting
  the day after the reference date (default 7), the same rule as gds-mock (spec 002).
- **Partition** — one (provider, day) pair: one `GET {base-url}/{provider-id}/flights?date=YYYY-MM-DD`.
- **Attempt** — one HTTP request for a partition, including reading and parsing its whole body.
- **Record** — one JSON object, XML `itinerary` element or CSV line. It becomes either one **offer**
  (`FlightOffer`) or one **malformed record**.
- **Day aggregation** — the spec 001 aggregation of the successful partitions of one horizon day.

**Assumption (partitioning):** providers partition their feed by the **UTC date of the itinerary's first departure**
(true for gds-mock; its injected `ALREADY_DEPARTED` itineraries are served under one partition day by every
provider). The itinerary identity includes the departure instants, so two offers of the same itinerary always fall
in the same day partition, and duplicates only need to be merged within a day. If a provider broke this rule, the
same itinerary served under two days would be produced twice (one per day): a duplicate, never a lost offer.

## Scope

**In scope**
- Adapters for Mock-Alpha (JSON), Mock-Beta (XML) and Mock-Gamma (CSV) that parse the body as a stream and
  normalise to spec 001 types.
- An ingestion use case (framework-free) that, for each horizon day, fetches that day from all providers in
  parallel, aggregates it, publishes the day's result and releases it; days run concurrently up to a limit.
- Resilience per provider in infrastructure: timeouts, attempt and run deadlines, retries with backoff and jitter,
  circuit breaker.
- The ingestion report, an outbound port for day results with an in-memory adapter, configuration properties
  (including the markup percentage deferred by spec 001).
- An optional end-to-end IT against a running gds-mock (tag `e2e`, excluded from the default build), and a
  measurement of the ingestion's memory footprint at 1,000,000 itineraries.

**Out of scope**
- PostgreSQL, Redis, scheduling, `POST/GET /api/v1/ingestions`, storing reports (next spec).
- The "no concurrent runs" guard (ADR-0006): it belongs with the trigger in the next spec. Until then the use case
  documents that it must not be invoked concurrently.
- Gzip, authentication to providers, rate limiting.
- Running the `e2e` IT in CI (later PR).

## Acceptance criteria

**Parsing and normalisation** (each adapter; formats in [providers.md](../../docs/providers.md))
- **AC-1** — Given a valid feed from each provider, when it is parsed, then every record becomes one `FlightOffer`
  with the provider's id, its segments in order, airports, a EUR base price and instants: Alpha
  `Instant.ofEpochMilli`, Beta ISO-8601 offset date-time, Gamma local date-time + IANA zone.
- **AC-2** — Given a Gamma itinerary whose connection crosses time zones (e.g. `OPO 12:55 Europe/Lisbon` →
  `BOS 12:15 America/New_York`), then the instants are correct (a 4 h 20 min flight) and the same itinerary from
  the three providers yields the same `ItineraryKey`.
- **AC-3** — Flight numbers are canonical: carrier + number without leading zeros. `FlightNumber` strips them when
  built, so Alpha `VY1`, Beta `<carrier>VY</carrier><flightNumber>0001</flightNumber>` and Gamma `VY0001` are all
  `VY1` (`VY0` stays `VY0`).
- **AC-4** — Given an empty feed (`[]`, an empty `<itineraries/>`, a header-only CSV), then the partition succeeds
  with zero offers.
- **AC-5** — Parsing is streamed: records are produced while the body is read, the parser never holds the document
  in memory (Jackson 3 streaming parser, StAX, line reader), and the body stream is closed on every outcome.

**Malformed records** (one reason per record: the first failing field in document order)
- **AC-6** — Given a record with a bad airport code, a non-positive price, an unparseable time (including Alpha's
  `"depTs":"TBD"`, a string in a numeric field) or a non-numeric / too long flight number, when the feed is parsed,
  then that record is skipped and counted under `BAD_AIRPORT_CODE`, `NON_POSITIVE_PRICE`, `UNPARSEABLE_TIME` or
  `BAD_FLIGHT_NUMBER`; any other unusable record (missing field, wrong CSV field count, unknown zone, non-EUR or
  non-numeric price, a segment with origin = destination, no segments) counts as `INVALID_RECORD`. The following
  records are still parsed and the partition succeeds.
- **AC-7 (error)** — Given a structurally broken document (invalid JSON/XML syntax, wrong root, unexpected CSV
  header) or a truncated one (body ends before the closing `]` / `</response>`, or the last CSV line has no
  terminating `\n`), then the attempt fails as `MALFORMED_DOCUMENT` and none of its offers or malformed counts are
  kept.
- **AC-8 (security)** — Given a Beta document with a `DOCTYPE`, an external entity or an internal entity expansion
  (XXE / "billion laughs"), then it is rejected as `MALFORMED_DOCUMENT` without resolving any entity or opening any
  file or URL.

**Day-partitioned parallel ingestion**
- **AC-9** — When an ingestion runs, then every (provider, day) partition of the horizon is requested once (plus
  retries). A day's partitions are fetched from all providers in parallel on virtual threads; up to
  `max-concurrent-days` days run at the same time, and at most `max-concurrent-partitions` partitions per provider
  are in flight. Given three providers each answering after a fixed latency, a day takes about the slowest
  provider's time, not the sum.
- **AC-10** — Each day has its own `OfferAggregator`, fed by a single consumer (the day's coordinator) with each
  partition as soon as that partition completes, and released once the day's result is published. The run's
  aggregation statistics are the sum of the day statistics, and equal those of one aggregator fed with all offers
  (given the partitioning assumption).
- **AC-11** — When a day finishes, then its result is published to the result port with the day, its itineraries
  and statistics, and the providers that contributed and that are missing. A day where every provider failed is
  not published.

**Resilience** (per provider, in infrastructure)
- **AC-12** — Given a partition whose first byte takes longer than `read-timeout`, or whose body stalls longer than
  `read-timeout` between bytes, then the attempt fails as `TIMEOUT` and is retried; if the retry succeeds, the
  partition succeeds with `attempts = 2`.
- **AC-13** — Given a body that keeps trickling bytes (never stalling for `read-timeout`) past `attempt-timeout`,
  then the attempt is aborted as `TIMEOUT` and retried. Given a run still going after `run-timeout`, then every
  unfinished partition is cancelled and fails as `RUN_TIMEOUT`, days are completed with the partitions they have,
  and the run returns its report. Cancelled requests are aborted at once (their sockets closed); none starts after
  the deadline, and none outlives the run by more than one `read-timeout`.
- **AC-14** — Given a `5xx`, a timeout, an I/O error (including a connection reset mid-body) or a malformed
  document, then the attempt is retried up to `max-attempts` in total, waiting an exponential backoff with jitter
  between attempts. Given a `4xx`, then the partition fails at once as `CLIENT_ERROR`, with no retry.
- **AC-15** — Given a partition answering `500` on every attempt, then that partition fails as `SERVER_ERROR` after
  `max-attempts`, and the provider's other partitions and the other providers are unaffected.
- **AC-16** — Given a provider in outage (`503` on every request), then its circuit breaker opens after
  `minimum-calls` failed attempts, and the remaining partitions of that provider fail as `CIRCUIT_OPEN` without an
  HTTP request and without retries. Breakers live across runs: a run that starts with an open breaker
  short-circuits until `open-wait` elapses, then half-open probes decide. `4xx` responses are not breaker failures.
- **AC-17** — Given the default breaker settings and a provider failing 10 % of its requests at random (fixed seed,
  the mock's default error rate), then the breaker never opens; it opens only on sustained failure (≥ 80 % of
  the last 10 attempts).

**Partial results and atomicity**
- **AC-18** — Given an attempt that fails mid-stream after some records were parsed, then none of its offers reach
  the aggregator and its malformed counts are dropped; given a retry that then succeeds, its offers are counted
  exactly once (no duplicates). Offers reach a day's aggregator only from successfully completed partitions.
- **AC-19** — Given failed partitions or providers, then the ingestion still completes and returns a report: a
  provider is `COMPLETED` if all its partitions succeeded, `PARTIAL` if some did, `FAILED` if none did; the
  ingestion is `COMPLETED` if every provider completed, `FAILED` only if every provider failed, otherwise `PARTIAL`.
- **AC-20** — Given every provider down, when the ingestion runs, then it returns a `FAILED` report, publishes
  nothing and throws no exception. A `PARTIAL` ingestion publishes every day that has at least one successful
  partition, naming the missing providers (AC-11).

**Report and reconciliation**
- **AC-21** — The report contains: run id, start/end instants and duration, overall status; per provider: status,
  partitions requested / succeeded / failed (each failed one with its day and failure kind: `TIMEOUT`,
  `SERVER_ERROR`, `CLIENT_ERROR`, `IO_ERROR`, `MALFORMED_DOCUMENT`, `CIRCUIT_OPEN`, `RUN_TIMEOUT`, or
  `UNEXPECTED_ERROR` for a bug in an adapter, which also fails only its partition), total attempts,
  duration, the circuit breaker state at the end of the run, records received, offers received and malformed
  records per reason; per day: contributing and missing providers, published or not, and its statistics; and the
  summed spec 001 `AggregationStatistics`. Per provider, `records received = offers received + Σ malformed`, and
  offers received equals the aggregation's received count for that provider.
- **AC-22** — Given gds-mock with no latency and no errors and the same reference date and horizon, when an
  ingestion completes, then per provider `records received` = `providers.records`, malformed per reason =
  `providers.malformed`, and the summed statistics equal `expectedAggregation` (offers received per provider,
  discarded per reason, duplicates merged, itineraries produced) of `GET /admin/world/stats`; and each day's
  statistics equal `GET /admin/world/stats?date=<day>`. Checked by the `e2e` IT and, without a running mock, by an
  IT that serves recorded mock feeds through WireMock.

**Configuration**
- **AC-23** — Base URLs, horizon, markup, concurrency, deadlines, timeouts, retry and breaker settings are
  configuration properties with the defaults below, overridable per provider for resilience. Invalid values
  (non-positive durations or counts, `max-attempts` < 1, `attempt-timeout` ≤ `read-timeout`, threshold outside
  (0, 100], horizon outside 1..30, a configured provider without an adapter or an adapter without a base URL) stop
  the startup with a message naming the property.

## Configuration (defaults)

| Property | Default | Why |
|---|---|---|
| `aggregator.providers.<id>.base-url` | `http://localhost:8081` | gds-mock serves the three providers (compose will set `http://gds-mock:8081` when the aggregator joins it, next spec) |
| `aggregator.ingestion.horizon-days` | `7` | Same horizon as gds-mock; a day outside it gets `400` |
| `aggregator.ingestion.markup-percentage` | `5` | Spec 001 / ADR-0010 |
| `aggregator.ingestion.max-concurrent-days` | `2` | Memory is bounded by the days in flight (see *Memory*); 7 days ⇒ 4 rounds × ≈ 3.8 s (mean of the slowest of 3 latencies) ≈ 15 s per run |
| `aggregator.ingestion.max-concurrent-partitions` (per provider) | `2` | Caps the load on each provider independently of the day limit |
| `aggregator.ingestion.run-timeout` | `10m` | Below the 15 min schedule (ADR-0006): a stuck run can never block the next ones |
| `…resilience.default.connect-timeout` | `1s` | Local network; a refused connection should fail fast |
| `…resilience.default.read-timeout` | `6s` | Above the mock's 5,000 ms maximum latency: ingestion runs in the background, and a shorter timeout would retry ~40 % of calls; also the max gap while streaming the body |
| `…resilience.default.attempt-timeout` | `60s` | Total time per attempt: about 10× a normal one (≤ 5 s latency + ≈ 1.5 s for the largest 1M-volume day partition, Beta) |
| `…resilience.default.max-records-per-partition` | `500000` | About 5× the largest provider-day partition at 1M itineraries (≈ 90,000): bounds one attempt's buffer if a provider streams without end (added after review) |
| `…resilience.default.retry.max-attempts` | `3` | At a 10 % error rate a partition fails all 3 with p ≈ 0.1 %, vs 10 % without retries; 21 partitions ⇒ ≈ 2 % of runs miss one partition |
| `…resilience.default.retry.initial-backoff` / `multiplier` / `jitter` | `500ms` / `2` / `0.5` | 0.5 s then 1 s (± 50 %): spreads retries of parallel partitions, small next to the latency |
| `…resilience.default.circuit-breaker.sliding-window` / `minimum-calls` | `10` / `10` attempts | A `503` outage answers at once, so 10 attempts take ≈ 3 s; the rest of the provider's partitions are short-circuited |
| `…resilience.default.circuit-breaker.failure-rate-threshold` | `80` % | Opens on sustained failure only: with 10 % random errors, ≥ 8 of 10 failed has p ≈ 4 × 10⁻⁷ per window |
| `…resilience.default.circuit-breaker.open-wait` | `30s` | Covers the rest of a typical run; the next scheduled run (15 min) probes again |
| `…resilience.default.circuit-breaker.half-open-calls` | `2` | Two successful probes close it |

Worst case per partition ≈ 3 × 60 s + 1.5 s backoff; the run deadline caps the whole run at 10 min.

## Design (details in the ADRs)

- Feature package `com.andresps.flights.ingestion`. **Application** (plain Java): `IngestFlightsUseCase` (port/in)
  returning `IngestionReport`; port/out `ProviderFeed` (one per provider: `provider()`, `fetch(day)` → a fully parsed
  partition or a `PartitionFetchException` with its failure kind and attempts, `circuitState()`) and
  `DayAggregationSink`. `IngestionService` coordinates days and partitions on virtual threads, builds one
  `OfferAggregator` per day (all sharing a validator whose clock is read once per run, ADR-0011) and enforces the run
  deadline.
- **Infrastructure**: `adapter/out/provider/{alpha,beta,gamma}` (one parser + feed per format, a shared HTTP and
  resilience base), Resilience4j `Retry` around `CircuitBreaker` around one attempt with its deadline,
  `adapter/out/memory` for the sink, `config` for properties and wiring. A new provider is a new adapter +
  configuration; the use case doesn't change.
- **Partition atomicity ([ADR-0015](../../docs/adr/0015-atomic-partition-buffers.md)):** each attempt parses into its
  own buffer, handed over only when the attempt completes.
- **Day-partitioned aggregation ([ADR-0016](../../docs/adr/0016-day-partitioned-aggregation.md)):** one aggregator
  per day, a single consumer per day, statistics summed.
- **Resilience ([ADR-0014](../../docs/adr/0014-provider-resilience.md)):** Resilience4j 2.4.0 core modules
  (`circuitbreaker`, `retry`), whose only dependency is `slf4j-api`, so they are compatible with Spring Boot 4 /
  Java 21; no Spring integration module. HTTP via Spring `RestClient` reading the body as an `InputStream`.

## Domain model impact

- `FlightNumber` strips leading zeros from the number when built (AC-3), so the canonical form is the identity.
  Amends spec 001's note "flight numbers are compared as given".
- `AggregationStatistics` gains `empty()` and `plus(other)` (counter-wise sum), as spec 001 anticipated for
  partitioned aggregators.
- New types of the ingestion feature, in `com.andresps.flights.ingestion.domain` (plain Java): `IngestionReport`,
  `ProviderReport`, `DayReport`, `PartitionFailure`, `IngestionStatus`, `FailureKind`, `MalformedReason`,
  `CircuitState`, `ProviderPartition`, `DayAggregation`.

## Non-functional requirements

- **Memory:** the working set is bounded by the **days in flight**, not by the world. At 1,000,000 itineraries a
  day holds ≈ 143,000 distinct itineraries and ≈ 265,000 records over the three providers (≈ 88,000 per partition).
  Per day: the aggregator (≈ 150–215 MB at the ADR-0011 estimate of 1–1.5 KB per entry) plus at most three partition
  buffers being parsed (≈ 45–90 MB each, released as soon as fed) ⇒ ≲ 0.5 GB per day, ≲ 1 GB for 2 days, versus
  ~1.5–2 GB for one global aggregator with buffers. At the default 100,000 volume, about a tenth. What the sink
  keeps is the sink's business: the in-memory adapter keeps every published day (the whole world); the PostgreSQL
  adapter will write and drop. The footprint is **measured at 1M in this PR** (ingestion working set with a
  discarding sink, and with the in-memory sink) and reported in `SOLUTION.md`.
- **Security:** XXE-safe StAX (`SUPPORT_DTD` and external entities off); untrusted amounts bounded by `Money`.
- **Tests:** WireMock (`wiremock-standalone` 3.13.x) for HTTP behaviour; timeouts, latencies, deadlines and backoffs
  scaled to milliseconds in test configuration; random failures seeded; no timing assertion tighter than a generous
  bound. The `e2e` tag is excluded by default and run with `./mvnw verify -pl flight-aggregator -Pe2e-tests` against
  a running gds-mock (`deterministic` profile).
- **Observability:** one INFO summary per run, WARN per failed partition, DEBUG per attempt.

## Requirements for the persistence spec

- Publishing a day with missing providers (`PARTIAL`) must **not silently wipe** a missing provider's previous good
  data for that day: the store keeps that provider's last successful offers for the day (and marks them as such)
  until a run fetches the provider again. The day result carries the contributing and missing providers for this.
- Until then, the in-memory adapter simply replaces a day's snapshot on each publication.

## Open questions

All resolved (2026-11-05):

- [x] Q1 Normalise flight numbers in the domain (`FlightNumber`) — yes.
- [x] Q2 `max-concurrent-partitions` = 2 per provider — yes; aggregation is per day, with `max-concurrent-days` = 2.
- [x] Q3 Read timeout 6 s — yes (background ingestion; a short timeout would retry ~40 % of calls).
- [x] Q4 Retry a malformed/truncated document and count it as a breaker failure — yes.
- [x] Q5 Publish `PARTIAL` — yes, with the persistence requirement above.
- [x] Q6 No deadline — **no**: total deadline per attempt (60 s) and per run (10 min) (AC-13).
- [x] Q7 `BAD_FLIGHT_NUMBER` and `INVALID_RECORD` — yes.
- [x] Added by the author: aggregation per day with summed statistics (AC-10, AC-11), breaker thresholds that never
  open at the mock's normal 10 % error rate, with a seeded test (AC-17).
