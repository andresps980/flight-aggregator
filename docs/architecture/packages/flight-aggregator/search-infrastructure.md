# search · infrastructure

`com.andresps.flights.search.infrastructure.adapter.in.web`, `….adapter.out.persistence`, `….adapter.out.cache`, `….config`

Everything technical about search (spec 005). **web** exposes `GET /api/v1/itineraries` and `GET /api/v1/catalog` and
maps domain views to `*Response` records (documented in OpenAPI). **persistence** builds the keyset SQL — `limit + 1`
rows `WHERE (final_price, id) > cursor ORDER BY final_price, id`, served by the two search indexes
([ADR-0008](../../../adr/0008-keyset-pagination.md), [ADR-0020](../../../adr/0020-search-indexes.md)). **cache**
decorates the two query ports with Redis cache-aside keyed by the data version, and degrades to PostgreSQL when Redis
is slow or down ([ADR-0022](../../../adr/0022-search-cache.md)). Redis code lives only in `cache`, SQL only in
`persistence` (ArchUnit).

## web

```mermaid
classDiagram
    %% package: com.andresps.flights.search.infrastructure.adapter.in.web
    class ItinerarySearchController {
        GET /api/v1/itineraries
        +search(origin, destination, date, maxPrice, carrier, limit, cursor) ItineraryPageResponse
    }
    class CatalogController {
        GET /api/v1/catalog
        +catalog() CatalogResponse
    }
    class ItineraryPageResponse {
        <<record>>
        +List~ItineraryResponse~ items
        +String nextCursor
        +boolean hasMore
        +from(SearchPage page)$ ItineraryPageResponse
    }
    class ItineraryResponse {
        <<record>>
        +from(ItineraryView view)$ ItineraryResponse
    }
    class SegmentResponse {
        <<record>>
        +from(SegmentView segment)$ SegmentResponse
    }
    class FreshnessResponse {
        <<record>>
        +from(Freshness freshness)$ FreshnessResponse
    }
    class CatalogResponse {
        <<record>>
        +from(Catalog catalog)$ CatalogResponse
    }

    ItinerarySearchController ..> ItineraryPageResponse
    CatalogController ..> CatalogResponse
    ItineraryPageResponse *-- "0..*" ItineraryResponse
    ItineraryResponse *-- "1..*" SegmentResponse
    ItineraryResponse *-- FreshnessResponse
    CatalogResponse ..> FreshnessResponse
```

## persistence

```mermaid
classDiagram
    %% package: com.andresps.flights.search.infrastructure.adapter.out.persistence
    class PostgresItinerarySearchQuery {
        +find(SearchCriteria criteria, PagePosition after, int limit, Instant now) SearchSlice
    }
    class PostgresCatalogQuery {
        +find(Instant now) Catalog
    }
    class PostgresDataVersionQuery {
        +current() long
    }
    class ItinerarySearchSql {
        +page(SearchCriteria criteria, PagePosition after, int rows, Instant now)$ SqlQuery
    }
    class ReadModelRows {
        itinerary(ResultSet rs)$ ItineraryView
        freshness(ResultSet rs)$ Freshness
    }

    PostgresItinerarySearchQuery ..> ItinerarySearchSql
    PostgresItinerarySearchQuery ..> ReadModelRows
    PostgresCatalogQuery ..> ReadModelRows
```

Codes are compared as `bpchar` placeholders so the `char(n)` columns are not cast and the indexes stay usable.

## cache

```mermaid
classDiagram
    %% package: com.andresps.flights.search.infrastructure.adapter.out.cache
    class CachingItinerarySearchQuery {
        +find(SearchCriteria criteria, PagePosition after, int limit, Instant now) SearchSlice
    }
    class CachingCatalogQuery {
        memoised per data version, single-flight
        +find(Instant now) Catalog
    }
    class CacheStore {
        <<interface>>
        +get(String key) Optional~String~
        +put(String key, String value, Duration ttl)
    }
    class ResilientCacheStore {
        100 ms timeouts, breaker 30 s
        +get(String key) Optional~String~
        +put(String key, String value, Duration ttl)
    }
    class RedisCacheStore {
        +get(String key) Optional~String~
        +put(String key, String value, Duration ttl)
    }
    class SearchCacheKeys {
        +search(long version, SearchCriteria criteria, PagePosition after, int limit)$ String
        +catalog(long version)$ String
    }
    class SearchCacheCodec {
        +write(SearchSlice slice) String
        +readSlice(String value) SearchSlice
        +write(Catalog catalog) String
        +readCatalog(String value) Catalog
    }
    class CacheMetrics {
        counter search.cache, tag result
        hit()
        miss()
        error()
        bypassed()
    }

    ResilientCacheStore ..|> CacheStore
    RedisCacheStore ..|> CacheStore
    ResilientCacheStore o-- CacheStore : delegate (Redis)
    CachingItinerarySearchQuery --> CacheStore
    CachingCatalogQuery --> CacheStore
    CachingItinerarySearchQuery ..> SearchCacheKeys
    CachingCatalogQuery ..> SearchCacheKeys
    CachingItinerarySearchQuery --> SearchCacheCodec
    CachingCatalogQuery --> SearchCacheCodec
    CachingItinerarySearchQuery --> CacheMetrics
    CachingCatalogQuery --> CacheMetrics
    ResilientCacheStore --> CacheMetrics
```

At runtime the chain is `CachingItinerarySearchQuery → ResilientCacheStore → RedisCacheStore`, and on a miss
`→ PostgresItinerarySearchQuery`. A hit drops itineraries that departed since the page was cached.

## config

```mermaid
classDiagram
    %% package: com.andresps.flights.search.infrastructure.config
    class SearchConfiguration {
        composition root of the feature
    }
    class SearchProperties {
        <<record>>
        +Cache cache
    }
    SearchConfiguration ..> SearchProperties : binds aggregator.search.*
```

**Interactions with other packages**

- Controllers → `SearchItineraries` / `GetCatalog` ([search · application](search-application.md)); errors →
  `shared.infrastructure.rest.GlobalExceptionHandler`.
- `PostgresItinerarySearchQuery` and `CachingItinerarySearchQuery` implement `ItinerarySearchQuery`;
  `PostgresCatalogQuery` and `CachingCatalogQuery` implement `CatalogQuery`; `PostgresDataVersionQuery` implements
  `DataVersionQuery` (`search.application.port.out`). The decorators hold the PostgreSQL adapter only as the port
  (ArchUnit forbids the cache from touching `persistence` or JDBC).
- `SearchConfiguration` wires `Caching* → Postgres*` when `aggregator.search.cache.enabled` (default), the bare
  PostgreSQL adapters otherwise, and registers `CacheMetrics` on Micrometer's `MeterRegistry`.
- → PostgreSQL reads `itinerary` joined with `day_snapshot`, and `data_version`, all written by `ingestion`.
- The runtime sequence, including Redis down, is in [flows.md](../../flows.md#search-request).
