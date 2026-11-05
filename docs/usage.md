# Usage

> The aggregator exposes no business API yet: the core aggregation domain ([spec 001](../specs/001-itinerary-aggregation/spec.md))
> is plain Java with no HTTP surface, and the ingestion and search APIs will be documented here as they are
> implemented. The mock GDS providers ([spec 002](../specs/002-gds-mock/spec.md)) are documented
> [below](#gds-mock-mock-gds-providers).

## Build and test

| Task | Command |
|---|---|
| Full build (unit + IT + coverage gate + format check, both modules) | `./mvnw verify` |
| Also run the slow volume tests (tagged `slow`: 1,000,000-record generation and streaming) | `./mvnw verify -Pslow-tests` |
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

### Configuration

No application-specific properties yet. The markup percentage becomes configurable when the ingestion use case is
wired (later spec).

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
