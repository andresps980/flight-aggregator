# gds-mock

Mock of the three external GDS providers described in [docs/exercise.md](../docs/exercise.md), built from
[spec 002](../specs/002-gds-mock/spec.md). It is a test double of external systems: a standalone Spring Boot app
that shares no code with `flight-aggregator`.

| Provider | Endpoint | Format | Times |
|---|---|---|---|
| Mock-Alpha | `GET /mock-alpha/flights` | raw JSON array | epoch milliseconds |
| Mock-Beta | `GET /mock-beta/flights` | deeply nested XML | ISO-8601 with offset |
| Mock-Gamma | `GET /mock-gamma/flights` | CSV, one itinerary per line | local date-time + IANA zone |

- **Data:** a seeded, deterministic world of 100,000 itineraries by default (up to 1,000,000) over the next 7 days.
  It includes cross- and same-provider duplicates, business-invalid itineraries (one per spec 001 discard reason)
  and malformed records. Feeds are streamed while generated, and `?date=` selects one day.
- **Chaos:** 100–5000 ms latency and 10 % `500`s per request by default. `POST /admin/providers/{id}/outage` forces
  `503`s until `DELETE`. The `deterministic` profile turns chaos off.
- **Reconciliation:** `GET /admin/world/stats` returns the expected figures for the current settings.

```bash
docker compose up --build                                        # from the repository root
./mvnw -pl gds-mock spring-boot:run -Dspring-boot.run.profiles=deterministic
```

Endpoints, configuration and the outage demo: [docs/usage.md](../docs/usage.md#gds-mock-mock-gds-providers).
Formats with examples: [docs/providers.md](../docs/providers.md). Design: [ADR-0012](../docs/adr/0012-single-gds-mock-application.md),
[ADR-0013](../docs/adr/0013-stateless-seeded-streamed-generation.md).
