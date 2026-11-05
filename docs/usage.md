# Usage

> The aggregator exposes no business API yet: the core aggregation domain ([spec 001](../specs/001-itinerary-aggregation/spec.md))
> and the provider ingestion ([spec 003](../specs/003-provider-ingestion/spec.md)) have no HTTP surface; the ingestion
> trigger and the search API will be documented here as they are implemented. The mock GDS providers
> ([spec 002](../specs/002-gds-mock/spec.md)) are documented [below](#gds-mock-mock-gds-providers).

## Build and test

| Task | Command |
|---|---|
| Full build (unit + IT + coverage gate + format check, both modules) | `./mvnw verify` |
| Also run the slow volume tests (tagged `slow`: 1,000,000-record generation and streaming) | `./mvnw verify -Pslow-tests` |
| Requirement for the ingestion tests | A current JDK 21 (tzdata ≥ 2026e, e.g. Temurin 21.0.13): the recorded provider feeds embed time-zone rules, and an older JVM fails fast saying so |
| Also run the end-to-end ingestion test (tagged `e2e`, needs gds-mock running in the `deterministic` profile) | `./mvnw -pl flight-aggregator verify -Pe2e-tests` |
| Start the stack (currently only gds-mock) | `docker compose up --build` |

## flight-aggregator

### Run

```bash
./mvnw -pl flight-aggregator spring-boot:run
```

The `flight-aggregator` service starts on port `8080` (override with `--server.port=9090` or `SERVER_PORT`).

### API

#### Operations

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Health (also `/actuator/health/liveness` and `/readiness`) |
| `GET /actuator/info` | Build/app info |

```bash
curl http://localhost:8080/actuator/health
```

```json
{ "status": "UP" }
```

Errors are returned as [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details: domain validation errors
map to `400`.

### Domain behaviour (spec 001)

How offers become sellable itineraries. This is the behaviour the future ingestion run will expose through its report.

| Step | Rule |
|---|---|
| Malformed data | Rejected when the value objects are built (e.g. airport not 3 letters, base price ≤ 0, non-EUR, amount ≥ 1,000,000,000 or more than 10 decimals). Never reaches the aggregation; the ingestion report will count it per provider. |
| Validation | Discarded with one reason, first match in this order: `MIXED_AIRLINES`, `ARRIVAL_NOT_AFTER_DEPARTURE`, `SEGMENTS_NOT_CHAINED`, `REVISITED_AIRPORT`, `CONNECTION_TOO_SHORT` (< 45 min), `LAYOVER_TOO_LONG` (> 24 h), `ALREADY_DEPARTED`. |
| Identity | Same flight numbers (with airline), segment airports and departure instants ⇒ same itinerary, whatever the provider. |
| Selection | Lowest base price → provider id → earliest arrivals → canonical representation. Independent of input order. |
| Pricing | Final price = base × (1 + markup), 5% by default, EUR, 2 decimals, `HALF_EVEN`. |
| Statistics | Received per provider, discarded per reason, duplicates merged, itineraries produced; received = discarded + merged + produced. |

### Provider ingestion (spec 003)

An ingestion run fetches every (provider, day) partition of the horizon (the 7 UTC days after today) from the three
providers, aggregates each day with the rules above and publishes it. It has no trigger yet: scheduling and
`POST /api/v1/ingestions` come with persistence in the next spec, so today it runs from the integration tests and the
`e2e` test below. Results are kept in memory.

| Behaviour | Rule |
|---|---|
| Parsing | Streamed (Jackson 3 parser, StAX, line reader). Times become UTC instants; flight numbers lose leading zeros (`VY0001` = `VY1`). |
| Malformed record | Skipped and counted per provider under one reason (first failing field): `BAD_AIRPORT_CODE`, `BAD_FLIGHT_NUMBER`, `NON_POSITIVE_PRICE`, `UNPARSEABLE_TIME`, `INVALID_RECORD`. Never stops the feed. |
| Broken document | Invalid or truncated JSON/XML/CSV, a wrong root or header, any XML `DOCTYPE` (XXE-safe): the attempt fails as `MALFORMED_DOCUMENT` and leaves nothing behind. |
| Retries | `5xx`, timeouts, I/O errors and broken documents: up to 3 attempts, 500 ms then 1 s (±50 % jitter). `4xx` (and any other non-2xx status): no retry. |
| Circuit breaker | Per provider, opens when ≥ 80 % of the last 10 attempts failed; then calls fail at once as `CIRCUIT_OPEN` for 30 s, after which 2 probes decide. Survives across runs. |
| Deadlines | 6 s without data (first byte or mid-body), 60 s per attempt, 10 min per run (`RUN_TIMEOUT`). |
| Partial results | A failed partition never fails the run. Provider: `COMPLETED` / `PARTIAL` / `FAILED` by its partitions; run: `FAILED` only if every provider failed. A day is published if at least one provider delivered it, naming the missing ones. |
| Report | Per provider: partitions, failures (day, kind, attempts), attempts, duration, breaker state, records, offers, malformed per reason. Per day: contributing/missing providers, published, statistics. Plus the summed spec 001 statistics. |

Run the end-to-end check against a real mock (it reconciles every figure with `/admin/world/stats`):

```bash
./mvnw -pl gds-mock spring-boot:run -Dspring-boot.run.profiles=deterministic   # terminal 1 (or docker compose up)
./mvnw -pl flight-aggregator verify -Pe2e-tests                                # terminal 2; -De2e.gds-mock.url=… to change
```

### Configuration

All under `aggregator.*` (defaults in `application.yml`, reasons in [spec 003](../specs/003-provider-ingestion/spec.md)
and [ADR-0014](adr/0014-provider-resilience.md)). Invalid values stop the startup with a message naming the property.
As in gds-mock, per-provider keys contain a dash, so override them with command-line arguments or
`SPRING_APPLICATION_JSON` rather than plain environment variables.

| Property | Default | Meaning |
|---|---|---|
| `aggregator.providers.<id>.base-url` | `http://localhost:8081` | Where provider `<id>` (`mock-alpha`, `mock-beta`, `mock-gamma`) is served; every provider with an adapter needs one |
| `aggregator.providers.<id>.resilience.*` | — | Per-provider override of any `aggregator.resilience.default.*` value |
| `aggregator.ingestion.horizon-days` | `7` | UTC days after today to ingest, 1..30 (must match the mock's horizon) |
| `aggregator.ingestion.markup-percentage` | `5` | OTA markup on the selected base price |
| `aggregator.ingestion.max-concurrent-days` | `2` | Days aggregated at the same time; memory grows with it (see SOLUTION.md) |
| `aggregator.ingestion.max-concurrent-partitions` | `2` | Requests in flight per provider |
| `aggregator.ingestion.run-timeout` | `10m` | Deadline of a whole run (below the 15 min schedule) |
| `aggregator.resilience.default.connect-timeout` | `1s` | TCP connect timeout |
| `aggregator.resilience.default.read-timeout` | `6s` | Max wait for the first byte and between body bytes (above the 5 s max latency) |
| `aggregator.resilience.default.attempt-timeout` | `60s` | Total time per attempt; must be greater than `read-timeout` |
| `aggregator.resilience.default.max-records-per-partition` | `500000` | Records one attempt may buffer; more fails the attempt as `MALFORMED_DOCUMENT` (protects the heap from a runaway feed) |
| `aggregator.resilience.default.retry.max-attempts` | `3` | Attempts per partition, first one included |
| `aggregator.resilience.default.retry.initial-backoff` / `multiplier` / `jitter` | `500ms` / `2` / `0.5` | Wait before attempt n+1: `initial × multiplier^(n-1) × (1 ± jitter)` |
| `aggregator.resilience.default.circuit-breaker.sliding-window` / `minimum-calls` | `10` / `10` | Attempts considered, and needed before deciding |
| `aggregator.resilience.default.circuit-breaker.failure-rate-threshold` | `80` | Percentage of failed attempts that opens the breaker |
| `aggregator.resilience.default.circuit-breaker.open-wait` | `30s` | Time open before half-open probes |
| `aggregator.resilience.default.circuit-breaker.half-open-calls` | `2` | Probes that decide whether it closes |

```bash
./mvnw -pl flight-aggregator spring-boot:run \
  -Dspring-boot.run.arguments="--aggregator.providers.mock-beta.resilience.read-timeout=9s --aggregator.ingestion.max-concurrent-days=1"
```

## gds-mock (mock GDS providers)

A standalone app simulating the three external providers ([spec 002](../specs/002-gds-mock/spec.md),
[ADR-0012](adr/0012-single-gds-mock-application.md), [ADR-0013](adr/0013-stateless-seeded-streamed-generation.md)).
It serves a seeded, deterministic world of itineraries with duplicates, invalid itineraries and malformed records,
plus latency, random errors and a runtime outage switch. Format details and examples: [providers.md](providers.md).

### Run

```bash
docker compose up --build                                   # port 8081, healthcheck on /actuator/health/readiness
./mvnw -pl gds-mock spring-boot:run                         # same, from source
./mvnw -pl gds-mock spring-boot:run -Dspring-boot.run.profiles=deterministic   # no latency, no errors
```

Profiles: default (chaos on: 100–5000 ms latency, 10 % errors) and `deterministic` (no latency, no errors; the outage
switch still works). The seed is fixed (42) in both, so the data is the same for the same reference date.

### Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /mock-alpha/flights[?date=YYYY-MM-DD]` | Mock-Alpha feed, JSON array (streamed) |
| `GET /mock-beta/flights[?date=YYYY-MM-DD]` | Mock-Beta feed, nested XML (streamed) |
| `GET /mock-gamma/flights[?date=YYYY-MM-DD]` | Mock-Gamma feed, CSV (streamed) |
| `GET /admin/providers` | Chaos settings and outage state of each provider |
| `POST /admin/providers/{id}/outage` | Force an outage: the provider answers `503` until recovered (`204`) |
| `DELETE /admin/providers/{id}/outage` | Recover the provider (`204`) |
| `GET /admin/world/stats[?date=]` | Expected figures for the current settings (see below) |
| `GET /actuator/health` | Health (never affected by chaos or outages) |

- Without `date` a feed covers the whole horizon: the 7 UTC days after the reference date (today by default). With
  `date` it covers one of those days. The day partitions are disjoint and together equal the full feed. Departed
  itineraries (injected, departing the day before the reference date) are served under the day that generated them.
- Errors are RFC 9457 Problem Details (`application/problem+json`): `400` bad or out-of-horizon `date`, `404` unknown
  provider, `500` injected error, `503` forced outage. `400` and `404` are answered without latency.

```bash
curl -s "localhost:8081/mock-alpha/flights?date=$(date -u -v+1d +%F)" | head -c 400   # macOS; GNU: date -u -d tomorrow +%F
curl -s localhost:8081/mock-gamma/flights | head -3
curl -s -o /dev/null -w '%{http_code} %{size_download}B %{time_total}s\n' localhost:8081/mock-beta/flights
```

### Chaos and the outage demo

Each provider request first checks the outage switch (`503` at once), then waits a random latency in
`[latency-min, latency-max]`, then fails with `500` with probability `error-rate`. Chaos applies only to the three
feeds, never to admin or health endpoints.

```bash
curl -s localhost:8081/admin/providers                                # current settings
curl -s -X POST localhost:8081/admin/providers/mock-beta/outage       # Beta goes down
curl -s localhost:8081/mock-beta/flights                              # 503 Problem Details, immediately
curl -s -X DELETE localhost:8081/admin/providers/mock-beta/outage     # Beta recovers
```

A feed is split per day with `?date=`, so an ingestion run makes about 7 requests per provider, and each one gets
its own latency and error roll. At a 10 % error rate a provider fails at least one of its 7 partitions in about
half of the runs: the ingestion has to retry per partition and accept partial results.

### Expected figures

`GET /admin/world/stats` returns what the world contains and what spec 001's aggregation should report if fed every
well-formed record:

- distinct itineraries, invalid itineraries per kind, cross-provider duplicates;
- per provider: records, same-provider duplicates, malformed records per kind, itineraries whose records from that
  provider are all malformed;
- itineraries whose records are all malformed (total and per invalid kind): no client can parse them;
- `expectedAggregation`: offers received per provider, discarded per reason, duplicates merged, itineraries
  produced (received = discarded + merged + produced). `ALREADY_DEPARTED` counts hold while the aggregator's clock
  is on the reference date.

The first call computes the figures in one pass over the world (about 1.7 s at 1,000,000 itineraries). They are
cached until the reference date changes.

### Configuration

Every property can be set as a command-line argument, and every property except per-provider chaos overrides as an
environment variable (`gds.world.volume` → `GDS_WORLD_VOLUME`). Environment variables drop the dash in map keys
(`MOCK_BETA` binds to `mockbeta`), so per-provider overrides need an argument or `SPRING_APPLICATION_JSON`, e.g.
`SPRING_APPLICATION_JSON='{"gds":{"chaos":{"providers":{"mock-beta":{"error-rate":0.5}}}}}'`. Out-of-range values and
unknown provider ids stop the startup with a message naming the property.

| Property | Default | Meaning |
|---|---|---|
| `gds.world.seed` | `42` | Seeds all data; same seed + reference date + settings ⇒ byte-identical feeds |
| `gds.world.volume` | `100000` | Distinct itineraries over the whole horizon, 1..1,000,000 (at most 239,976 per day) |
| `gds.world.horizon-days` | `7` | UTC days served after the reference date, 1..30 |
| `gds.world.reference-date` | today (UTC) | Fixed reference date, e.g. `2026-11-05` |
| `gds.world.invalid-fraction` | `0.05` | Business-invalid itineraries |
| `gds.world.malformed-fraction` | `0.01` | Malformed records |
| `gds.world.same-provider-duplicate-fraction` | `0.005` | Records repeated by the same provider at another price |
| `gds.chaos.default.latency-min` / `latency-max` | `100ms` / `5000ms` | Random latency range per request |
| `gds.chaos.default.error-rate` | `0.10` | Probability of an injected `500` |
| `gds.chaos.providers.<id>.*` | — | Per-provider override of any chaos value, e.g. `gds.chaos.providers.mock-beta.error-rate=0.5` |

```bash
GDS_WORLD_VOLUME=1000000 docker compose up --build    # full volume
```

At 1,000,000 itineraries the feeds are about 144 MB (Alpha), 332 MB (Beta) and 100 MB (Gamma), and the mock serves
them with a 256 MB heap (measurements in [SOLUTION.md](../SOLUTION.md#mock-gds-providers-spec-002)).
