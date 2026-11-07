# gds-mock · inventory · infrastructure

`com.andresps.gdsmock.inventory.infrastructure.adapter.in.web`, `….adapter.in.web.format`, `….config`

The HTTP face of the mock providers (spec 002, [ADR-0012](../../../adr/0012-single-gds-mock-application.md)).
`GET /{provider}/flights?date=` resolves the provider and day, then streams the generator's records straight into the
response through that provider's `ProviderFormat` — Mock-Alpha a JSON list, Mock-Beta nested XML, Mock-Gamma CSV — so
a 1M feed never sits in memory. `GET /admin/world/stats` reports the reconciliation figures, cached per horizon and
day. A new provider is a new `ProviderFormat` bean (ArchUnit checks every `*Format` implements it). Formats:
[docs/providers.md](../../../providers.md).

## web

```mermaid
classDiagram
    %% package: com.andresps.gdsmock.inventory.infrastructure.adapter.in.web
    class ProviderFeedController {
        GET /:providerId/flights?date=
        +flights(String providerId, String date) ResponseEntity~StreamingResponseBody~
    }
    class WorldStatsController {
        GET /admin/world/stats?date=
        +stats(String date) WorldStatsResponse
    }
    class ProviderFormat {
        <<interface>>
        +providerId() ProviderId
        +mediaType() MediaType
        +write(Iterator~ProviderRecord~ records, OutputStream out)
    }
    class ProviderFormats {
        +ids() List~ProviderId~
        +get(ProviderId provider) ProviderFormat
    }
    class FeedRequestResolver {
        +resolve(String providerId, String date) FeedRequest
    }
    class FeedRequest {
        <<record>>
        +ProviderId provider
        +Optional~LocalDate~ date
    }
    class WorldStatisticsCache {
        +get(Horizon horizon, Optional~LocalDate~ day) WorldStatistics
    }
    class WorldStatsResponse {
        <<record>>
        +from(Horizon horizon, LocalDate date, WorldStatistics stats)$ WorldStatsResponse
    }
    class InvalidDateException

    ProviderFeedController --> ProviderFormats
    ProviderFeedController --> FeedRequestResolver
    ProviderFormats o-- "3" ProviderFormat
    FeedRequestResolver ..> FeedRequest : returns
    FeedRequestResolver ..> InvalidDateException : throws
    WorldStatsController --> WorldStatisticsCache
    WorldStatsController --> FeedRequestResolver
    WorldStatsController ..> WorldStatsResponse
```

## format and config

```mermaid
classDiagram
    %% package: com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format
    %% package: com.andresps.gdsmock.inventory.infrastructure.config
    class AlphaJsonFormat {
        Jackson 3 streaming generator
        +write(Iterator~ProviderRecord~ records, OutputStream out)
    }
    class BetaXmlFormat {
        StAX writer
        +write(Iterator~ProviderRecord~ records, OutputStream out)
    }
    class GammaCsvFormat {
        BufferedWriter
        +write(Iterator~ProviderRecord~ records, OutputStream out)
    }
    class InventoryConfig {
        composition root
    }
    class WorldProperties {
        <<record>>
        +long seed
        +int volume
        +int horizonDays
        +LocalDate referenceDate
    }
    InventoryConfig ..> WorldProperties : binds gds.world.*
    InventoryConfig ..> AlphaJsonFormat : creates
    InventoryConfig ..> BetaXmlFormat : creates
    InventoryConfig ..> GammaCsvFormat : creates
```

The three formats implement `ProviderFormat` (previous diagram). A shared contract test runs the same round-trip,
empty-feed, streaming and client-disconnect checks against each.

**Interactions with other packages**

- → `WorldGenerator`, `WorldStatisticsCalculator` and the model records ([inventory · domain](inventory-domain.md)).
- → `gdsmock.shared.domain`: `ProviderId`; `InvalidDateException` extends `InvalidRequestException` (→ `400`), an
  unknown provider is `UnknownProviderException` (→ `404`).
- ← `ChaosInterceptor` ([chaos](chaos.md)) runs before `ProviderFeedController` and uses `FeedRequestResolver` to know
  which provider a request is for; `ChaosConfig` uses `ProviderFormats` for the provider ids. Inventory never depends
  on chaos.
- ← called by the aggregator's `HttpProviderFeed` (one request per provider and day) and by its e2e test (stats).
