# Usage

> The aggregator ingests the three providers on a schedule and on demand, stores the deduplicated itineraries in
> PostgreSQL ([spec 003](../specs/003-provider-ingestion/spec.md), [spec 004](../specs/004-persistence-and-pipeline/spec.md))
> and serves them through the search API ([spec 005](../specs/005-search-api/spec.md)) and the
> [dashboard](#dashboard-spec-006) ([spec 006](../specs/006-dashboard/spec.md)). The mock GDS providers
> ([spec 002](../specs/002-gds-mock/spec.md)) are documented [below](#gds-mock-mock-gds-providers).

## Build and test

| Task | Command |
|---|---|
| Full build (unit + IT + coverage gate + format check, both modules) | `./mvnw verify` |
| Also run the slow volume tests (tagged `slow`: 1,000,000-record generation and streaming, bulk load, search plans and latency at 1M rows) | `./mvnw verify -Pslow-tests` |
| Also run the end-to-end ingestion test (tagged `e2e`, needs gds-mock running in the `deterministic` profile) | `./mvnw -pl flight-aggregator verify -Pe2e-tests` |
| Full-stack smoke test: compose boot, ingestion, search through nginx, e2e test, provider outage (spec 007, also in CI) | `scripts/stack-smoke.sh` |
| Also run the 1M-row bulk load benchmark (tagged `slow`, ADR-0018) | `./mvnw -pl flight-aggregator verify -Pslow-tests -Dit.test=BulkLoadBenchmarkIT` |
| Frontend checks (Prettier, ESLint, tsc, Vitest with coverage gate) | `cd frontend && npm ci && npm run check` |
| Frontend image smoke test (needs Docker) | `bash frontend/docker/smoke-test.sh` |
| Start the stack (PostgreSQL, Redis, gds-mock, flight-aggregator, frontend) | `docker compose up --build` |

Integration tests start one PostgreSQL (`postgres:17`) and one Redis (`redis:8`) container (Testcontainers) for the
whole build, so Docker must be running.

## flight-aggregator

### Run

The easiest way is the whole stack ([Docker Compose](#docker-compose)). To run the aggregator from the build, start
PostgreSQL, Redis and gds-mock first (Redis is optional: without it, search reads PostgreSQL every time):

```bash
docker compose up -d postgres redis gds-mock
./mvnw -pl flight-aggregator spring-boot:run
```

The `flight-aggregator` service starts on port `8080` (override with `--server.port=9090` or `SERVER_PORT`). Flyway
creates or migrates the schema at startup; the first ingestion runs 10 s after startup, then every 15 min.

### Docker Compose

```bash
docker compose up --build        # postgres + redis + gds-mock + flight-aggregator + frontend
```

| Service | Port | Notes |
|---|---|---|
| `postgres` | `5432` (`POSTGRES_PORT`) | `postgres:17`, database/user `flights`, password `flights` (`POSTGRES_PASSWORD`); data in the named volume `postgres-data` (`docker compose down -v` deletes it) |
| `redis` | `6379` (`REDIS_PORT`) | `redis:8`, search cache only: no persistence, `maxmemory 256mb`, `allkeys-lru` |
| `gds-mock` | `8081` | Chaos on by default; `GDS_MOCK_PROFILE=deterministic GDS_CHAOS_DEFAULT_ERROR_RATE=0` turns it off (the profile alone keeps the compose file's 10 % errors), `GDS_WORLD_VOLUME=1000000` for the full volume |
| `flight-aggregator` | `8080` | Starts when the other three are healthy; healthy on `/actuator/health/readiness`; restarts if the JVM exits on `OutOfMemoryError` |
| `frontend` | `3000` (`FRONTEND_PORT`) | nginx serving the dashboard and proxying `/api`; starts when the aggregator is healthy; healthy on `/healthz`; `DEMO_CONTROLS` (default `true`) shows the demo panel and proxies `/gds-admin` |

Variables for the aggregator: `INGESTION_INITIAL_DELAY` (default `10s`), `INGESTION_FIXED_DELAY` (`15m`),
`AGGREGATOR_JAVA_TOOL_OPTIONS` (default `-Xmx768m -XX:+ExitOnOutOfMemoryError`, see SOLUTION.md). The compose file
maps them to `aggregator.*` properties, the datasource and `GDS_MOCK_URL`. For example, a 1M run without chaos:

```bash
GDS_WORLD_VOLUME=1000000 GDS_MOCK_PROFILE=deterministic GDS_CHAOS_DEFAULT_ERROR_RATE=0 docker compose up --build -d --wait
curl -X POST http://localhost:8080/api/v1/ingestions
docker compose exec postgres psql -U flights -c "SELECT departure_date, count(*) FROM itinerary GROUP BY 1 ORDER BY 1"
```

### API

#### Operations

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Health (also `/actuator/health/liveness` and `/readiness`) |
| `GET /actuator/info` | Build/app info |
| `GET /actuator/metrics/search.cache` | Search cache counter by `result` (`hit`, `miss`, `error`, `bypassed`) |
| `GET /api/v1/itineraries` | Search itineraries (spec 005) |
| `GET /api/v1/catalog` | Origins, destinations, carriers, dates and routes that can be searched (spec 005) |
| `GET /v3/api-docs`, `/swagger-ui.html` | OpenAPI description of every `/api/v1` endpoint, search and ingestion (committed copy: [`docs/api/openapi.json`](api/openapi.json), checked by `OpenApiContractIT`; `./mvnw verify` fails on any drift; regenerate it explicitly with `./mvnw -pl flight-aggregator verify -Popenapi-write`, see [frontend.md](frontend.md#contract-generated-types)) |
| `POST /api/v1/ingestions` | Start an ingestion run now (spec 004) |
| `GET /api/v1/ingestions/{id}` | A run: status, timestamps and, once finished, its report |
| `GET /api/v1/ingestions/latest` | The most recently started run |

```bash
curl http://localhost:8080/actuator/health
```

```json
{ "status": "UP" }
```

Errors are returned as [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details: domain validation errors
and malformed ids map to `400`, unknown resources to `404`, a run already in progress to `409`.

#### Search (spec 005)

`GET /api/v1/itineraries` returns itineraries **departing after now**, cheapest first (final price, then an internal
id), one page at a time. Every filter is optional and they combine with AND:

| Parameter | Format | Meaning |
|---|---|---|
| `origin`, `destination` | IATA airport, 3 letters, case-insensitive | First origin / final destination |
| `date` | `YYYY-MM-DD`, from yesterday to today + 365 days | UTC day of the first departure. Absent: every future day |
| `maxPrice` | ≥ 0, at most 2 decimals | Final price (EUR) at most this, inclusive |
| `carrier` | IATA airline, 2 characters A–Z/0–9 | The airline operating every segment |
| `limit` | 1–100, default 20 | Items per page |
| `cursor` | opaque | `nextCursor` of the previous page; only valid with the same filters |

```bash
curl 'http://localhost:8080/api/v1/itineraries?origin=MAD&destination=JFK&maxPrice=650&limit=2'
```

```json
{"items":[{"itineraryKey":"IB6251:MAD-JFK@2026-11-08T10:15:00Z","origin":"MAD","destination":"JFK",
  "departureDate":"2026-11-08","departureAt":"2026-11-08T10:15:00Z","arrivalAt":"2026-11-08T18:40:00Z",
  "carrier":"IB","stops":0,
  "segments":[{"flightNumber":"IB6251","origin":"MAD","destination":"JFK",
               "departureAt":"2026-11-08T10:15:00Z","arrivalAt":"2026-11-08T18:40:00Z"}],
  "basePrice":412.38,"finalPrice":433.00,"currency":"EUR","selectedProvider":"mock-alpha",
  "providers":["mock-alpha","mock-gamma"],
  "freshness":{"status":"COMPLETE","publishedAt":"2026-11-06T10:00:21Z","missingProviders":[],"staleSince":null}},
  …],
 "nextCursor":"djF8NDMzLjAwfDgyMTM0fDdmM2E5YzEyZTRiMDVkNjg","hasMore":true}
```

```bash
curl 'http://localhost:8080/api/v1/itineraries?origin=MAD&destination=JFK&maxPrice=650&limit=2&cursor=djF8NDMzLjAwfDgyMTM0fDdmM2E5YzEyZTRiMDVkNjg'
```

**Paging rules for clients:**
- Keep requesting while `hasMore` is `true`. A page served from the cache omits itineraries that departed after it
  was cached, so it **may be shorter than `limit`, or even empty, while `hasMore` is `true`**.
- There is no total count and no "page N": the cursor continues from the last item seen ([ADR-0008](adr/0008-keyset-pagination.md)).
  `limit` may change between pages; the filters may not (a cursor reused with other filters answers `400`).
- If an ingestion run replaces a day while you page, the walk continues from the same price position in the new data,
  so an itinerary at the boundary may be skipped or repeated, and prices may change between pages. Start again from
  the first page to get a consistent view.
- `freshness.status` is the itinerary's day: `COMPLETE`, `INCOMPLETE` (some providers missing, listed in
  `missingProviders`) or `STALE` (the latest run could not refresh the day, so its previous data is shown; see
  `staleSince`).

Invalid parameters answer `400` with a problem detail that names the parameter:

```http
HTTP/1.1 400
Content-Type: application/problem+json

{"type":"about:blank","title":"Invalid request","status":400,
 "detail":"origin must be a 3-letter IATA airport code","instance":"/api/v1/itineraries"}
```

`GET /api/v1/catalog` lists what can be searched now, to fill the dashboard's dropdowns:

```bash
curl http://localhost:8080/api/v1/catalog
```

```json
{"origins":["AMS","BCN","MAD"],"destinations":["JFK","LHR"],"carriers":["IB","VY"],
 "dates":[{"date":"2026-11-06","freshness":{"status":"COMPLETE","publishedAt":"…","missingProviders":[],"staleSince":null}}],
 "routes":[{"origin":"AMS","destinations":["JFK"]},{"origin":"MAD","destinations":["JFK","LHR"]}]}
```

The catalog is computed once per data version (and kept in memory too), so after the last flight of a route departs
it can still be listed until the next ingestion run; days before today are never listed.

Both endpoints are cached in Redis per data version: the first request after an ingestion run publishes data reads
PostgreSQL, the next identical ones are served from Redis ([ADR-0022](adr/0022-search-cache.md)). If Redis is down,
answers come from PostgreSQL (a WARN when the cache is switched off for 30 s, the `search.cache` metric counts
failures). `/actuator/health` reports Redis; readiness does not depend on it.

#### Ingestion runs (spec 004)

Runs never overlap, not even across instances: the scheduler skips its tick, and `POST` answers `409`.

```bash
curl -i -X POST http://localhost:8080/api/v1/ingestions
```

```http
HTTP/1.1 202
Location: http://localhost:8080/api/v1/ingestions/46369dfb-0078-4b6e-b27d-5d3e7c96e870
Content-Type: application/json

{"id":"46369dfb-0078-4b6e-b27d-5d3e7c96e870","trigger":"MANUAL","status":"RUNNING",
 "startedAt":"2026-11-06T09:13:20.944244Z","finishedAt":null,"durationMs":null,"failure":null,"report":null}
```

While it runs:

```http
HTTP/1.1 409
Content-Type: application/problem+json

{"type":"about:blank","title":"Ingestion already running","status":409,
 "detail":"Ingestion 46369dfb-0078-4b6e-b27d-5d3e7c96e870 is in progress","instance":"/api/v1/ingestions",
 "runningIngestionId":"46369dfb-0078-4b6e-b27d-5d3e7c96e870"}
```

Follow it with `GET /api/v1/ingestions/{id}` (or `/latest`). Statuses: `RUNNING`, `COMPLETED`, `PARTIAL` (some
partition failed, or a day could not be written), `FAILED` (every provider failed, an unexpected error, or the run
lost its lock), `ABORTED` (its process died; marked by the next run). Once finished, `report` holds the spec 003 report
plus, per day, what the store did:

```json
{"id":"…","trigger":"SCHEDULED","status":"COMPLETED","startedAt":"…","finishedAt":"…","durationMs":17514,
 "failure":null,
 "report":{"runId":"…","status":"COMPLETED","durationMs":17514,"storedItineraries":94543,"misfiledItineraries":0,
   "providers":[{"provider":"mock-alpha","status":"COMPLETED","partitionsRequested":7,"partitionsSucceeded":7,
                 "attempts":8,"failures":[],"circuitState":"CLOSED","recordsReceived":…,"malformed":{…},…}, …],
   "days":[{"day":"2026-11-07","contributing":["mock-alpha","mock-beta","mock-gamma"],"missing":[],
            "outcome":"REPLACED","published":true,"storedItineraries":13529,"misfiledItineraries":0,
            "statistics":{…}}, …],
   "statistics":{"offersReceivedByProvider":{…},"discardedByReason":{…},"duplicatesMerged":…,
                 "itinerariesProduced":94543,…}}}
```

| Day outcome | Meaning ([ADR-0017](adr/0017-day-snapshot-replacement.md)) |
|---|---|
| `REPLACED` | Every provider contributed; the day's rows were replaced atomically |
| `PUBLISHED_INCOMPLETE` | Written with missing providers: there was no snapshot, the stored one was no more complete, or it had been stale for more than `max-stale-age` |
| `KEPT_STALE` | The stored snapshot had a provider this run is missing (or every provider failed): it was kept and marked stale |
| `NO_DATA` | Every provider failed and there was nothing stored |
| `WRITE_FAILED` | The write failed and was rolled back; the previous snapshot is intact |

Itineraries whose first departure falls on another UTC day than their partition are not stored and are counted as
`misfiledItineraries` (`stored + misfiled = itineraries produced`).

### Domain behaviour (spec 001)

How offers become sellable itineraries; every count below appears in the ingestion run's report.

| Step | Rule |
|---|---|
| Malformed data | Rejected when the value objects are built (e.g. airport not 3 letters, base price ≤ 0, non-EUR, amount ≥ 1,000,000,000 or more than 10 decimals). Never reaches the aggregation; the ingestion report counts it per provider and reason. |
| Validation | Discarded with one reason, first match in this order: `MIXED_AIRLINES`, `ARRIVAL_NOT_AFTER_DEPARTURE`, `SEGMENTS_NOT_CHAINED`, `REVISITED_AIRPORT`, `CONNECTION_TOO_SHORT` (< 45 min), `LAYOVER_TOO_LONG` (> 24 h), `ALREADY_DEPARTED`. |
| Identity | Same flight numbers (with airline), segment airports and departure instants ⇒ same itinerary, whatever the provider. |
| Selection | Lowest base price → provider id → earliest arrivals → canonical representation. Independent of input order. |
| Pricing | Final price = base × (1 + markup), 5% by default, EUR, 2 decimals, `HALF_EVEN`. |
| Statistics | Received per provider, discarded per reason, duplicates merged, itineraries produced; received = discarded + merged + produced. |

### Provider ingestion (spec 003)

An ingestion run fetches every (provider, day) partition of the horizon (the 7 UTC days after today) from the three
providers, aggregates each day with the rules above and stores it in PostgreSQL (spec 004, see
[Ingestion runs](#ingestion-runs-spec-004)).

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
./mvnw -pl gds-mock spring-boot:run -Dspring-boot.run.profiles=deterministic   # terminal 1
./mvnw -pl flight-aggregator verify -Pe2e-tests                                # terminal 2; -De2e.gds-mock.url=… to change
```

The default compose stack runs chaos, which the test refuses; `scripts/stack-smoke.sh` runs it against a
deterministic stack (spec 007).

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
| `aggregator.ingestion.schedule.enabled` | `true` | Run on a schedule (`false`: only `POST /api/v1/ingestions`) |
| `aggregator.ingestion.schedule.initial-delay` | `10s` | First run after the application is ready |
| `aggregator.ingestion.schedule.fixed-delay` | `15m` | Wait after each scheduled run ends before the next |
| `aggregator.persistence.max-stale-age` | `2h` | How long a stale day snapshot is kept against partial data before partial data replaces it |
| `aggregator.persistence.run-retention` | `7d` | Ingestion runs older than this are deleted at the start of each run |
| `spring.datasource.hikari.maximum-pool-size` | `10` | Keep ≥ `max-concurrent-days` + 2: each day in flight writes on its own connection (the run lock uses a separate, non-pooled one) |
| `aggregator.search.cache.enabled` | `true` | Serve search and catalog through the Redis cache (`false`: always PostgreSQL) |
| `aggregator.search.cache.ttl` | `10m` | Lifetime of a cache entry (a safety net: the data version invalidates entries) |
| `spring.data.redis.host` / `port` | `localhost` / `6379` | Redis (`REDIS_HOST` / `REDIS_PORT`; compose: `redis`) |
| `spring.data.redis.timeout` / `connect-timeout` | `100ms` / `100ms` | Beyond this a Redis call counts as failed and the search reads PostgreSQL |
| `spring.datasource.url` / `username` / `password` | `jdbc:postgresql://localhost:5432/flights` / `flights` / `flights` | PostgreSQL (`SPRING_DATASOURCE_*` in compose) |
| `GDS_MOCK_URL` (environment) | `http://localhost:8081` | Base URL of all three providers at once (compose: `http://gds-mock:8081`) |
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

## Dashboard (spec 006)

Open <http://localhost:3000> after `docker compose up --build` (or run the Vite dev server, see
[frontend.md](frontend.md#run-it)). Structure, data flow and a code walkthrough: [frontend.md](frontend.md).

| Feature | Behaviour |
|---|---|
| Search | Origin, destination (only those served from the chosen origin), departure date (catalog dates with their freshness), max price (EUR, ≤ 2 decimals) and carrier; runs on *Search* / Enter |
| Shareable URL | The filters are the URL's query, with the API's names: `http://localhost:3000/?origin=MAD&destination=JFK&maxPrice=650`; reload and back / forward rerun that search |
| Results | Cheapest first; route, carrier, stops, duration, segments, **final price**, base price and markup, selected provider and the others, freshness badge. **Times in UTC** |
| Paging | *Load more* follows `nextCursor`; no total. Empty or short pages while `hasMore` is true are followed automatically (up to 5 empty pages in a row) |
| Data status | Latest run (status, trigger, times, stored itineraries), per provider (result, partitions, breaker, failures), per day; polled every 30 s, every 3 s while a run is `RUNNING`, paused in a hidden tab; a `FAILED` run warns that data may be stale |
| Refresh data | Starts a run (`POST /api/v1/ingestions`); a run in progress answers "already running" (`409`) |
| New data available | Offered (never automatic) when a run that published data finishes after the results were loaded; *Reload results* refreshes the catalog and the search |
| Charts | Price distribution and results by selected provider, of the loaded results |
| Demo controls | With `DEMO_CONTROLS=true` (compose default): toggle a gds-mock provider outage, then *Refresh data* to watch the provider fail, its breaker open and its days go `STALE` |

Container settings (`frontend` service):

| Variable | Default | Effect |
|---|---|---|
| `FRONTEND_PORT` (compose) | `3000` | Host port of the dashboard |
| `DEMO_CONTROLS` | `true` in compose, `false` in the image | Demo panel and the `/gds-admin/providers…` proxy to gds-mock; when off, `/gds-admin/` answers `404` |
| `AGGREGATOR_URL` | `http://flight-aggregator:8080` | Upstream for `/api/` |
| `GDS_MOCK_URL` | `http://gds-mock:8081` | Upstream for the demo controls |

```bash
DEMO_CONTROLS=false FRONTEND_PORT=8000 docker compose up --build -d --wait   # no demo panel, on port 8000
curl -s localhost:3000/config.json                                          # default stack: {"demoControls": true}
curl -s localhost:3000/api/v1/catalog | head -c 200                         # the API through nginx
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
them with a 256 MB heap (measurements in [SOLUTION.md](../SOLUTION.md#27-other-measured-results)).
