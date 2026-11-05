# Usage

> No business API is exposed yet. The core aggregation domain ([spec 001](../specs/001-itinerary-aggregation/spec.md))
> is plain Java with no HTTP surface; the ingestion and search APIs will be documented here as they are implemented.

## Run

```bash
./mvnw -pl flight-aggregator spring-boot:run
```

The `flight-aggregator` service starts on port `8080` (override with `--server.port=9090` or `SERVER_PORT`).

## API

### Operations

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

## Domain behaviour (spec 001)

How offers become sellable itineraries. This is the behaviour the future ingestion run will expose through its report.

| Step | Rule |
|---|---|
| Malformed data | Rejected when the value objects are built (e.g. airport not 3 letters, base price ≤ 0, non-EUR). Never reaches the aggregation; the ingestion report will count it per provider. |
| Validation | Discarded with one reason, first match in this order: `MIXED_AIRLINES`, `ARRIVAL_NOT_AFTER_DEPARTURE`, `SEGMENTS_NOT_CHAINED`, `REVISITED_AIRPORT`, `CONNECTION_TOO_SHORT` (< 45 min), `LAYOVER_TOO_LONG` (> 24 h), `ALREADY_DEPARTED`. |
| Identity | Same flight numbers (with airline), segment airports and departure instants ⇒ same itinerary, whatever the provider. |
| Selection | Lowest base price → provider id → earliest arrivals → canonical representation. Independent of input order. |
| Pricing | Final price = base × (1 + markup), 5% by default, EUR, 2 decimals, `HALF_EVEN`. |
| Statistics | Received per provider, discarded per reason, duplicates merged, itineraries produced; received = discarded + merged + produced. |

## Configuration

No application-specific properties yet. The markup percentage becomes configurable when the ingestion use case is
wired (later spec).
