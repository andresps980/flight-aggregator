# search · domain

`com.andresps.flights.search.domain`

The read side's own model (spec 005): validated search criteria, the keyset position and the opaque cursor that
carries it, page sizes, and the views returned to clients (itineraries with their day's freshness, the catalog of
filter values). It is separate from the aggregation model on purpose: search reads denormalized rows and never
rebuilds `Itinerary` objects. `SearchCriteria.fingerprint()` ties a cursor to the filters that issued it
([ADR-0021](../../../adr/0021-search-cursor.md)). Plain Java. Two diagrams: criteria and paging, then views.

## Criteria, cursor and paging

```mermaid
classDiagram
    %% package: com.andresps.flights.search.domain
    class SearchCriteria {
        <<record>>
        +AirportCode origin
        +AirportCode destination
        +LocalDate date
        +BigDecimal maxPrice
        +AirlineCode carrier
        +parse(String origin, String destination, String date, String maxPrice, String carrier)$ SearchCriteria
        +canonical() String
        +fingerprint() String
    }
    class PageSize {
        <<record>>
        +int value
        +parse(String raw)$ PageSize
    }
    class PagePosition {
        <<record>>
        +BigDecimal finalPrice
        +long id
    }
    class Cursor {
        <<record>>
        +PagePosition position
        +String fingerprint
        +encode() String
        +decode(String token)$ Cursor
        +requireIssuedFor(String expectedFingerprint)
    }
    class SearchSlice {
        <<record>>
        +List~ItineraryView~ items
        +PagePosition next
        +hasMore() boolean
        +withoutDepartedAt(Instant now) SearchSlice
    }
    class SearchPage {
        <<record>>
        +List~ItineraryView~ items
        +String nextCursor
        +boolean hasMore
    }
    class InvalidSearchRequestException

    Cursor *-- PagePosition
    SearchSlice --> PagePosition : next
    SearchCriteria ..> InvalidSearchRequestException : throws
    PageSize ..> InvalidSearchRequestException : throws
    Cursor ..> InvalidSearchRequestException : throws
```

`SearchSlice` is what a query port returns (items + the next keyset position); `SearchPage` is what the use case
returns (items + the encoded next cursor).

## Views and catalog

```mermaid
classDiagram
    %% package: com.andresps.flights.search.domain
    class ItineraryView {
        <<record>>
        +String itineraryKey
        +String origin
        +String destination
        +Instant departureAt
        +String carrier
        +int stops
        +List~SegmentView~ segments
        +BigDecimal finalPrice
        +List~String~ providers
        +Freshness freshness
        +departedAt(Instant now) boolean
    }
    class SegmentView {
        <<record>>
        +String flightNumber
        +String origin
        +String destination
        +Instant departureAt
        +Instant arrivalAt
    }
    class Freshness {
        <<record>>
        +FreshnessStatus status
        +Instant publishedAt
        +List~String~ missingProviders
        +Instant staleSince
        +of(boolean complete, Instant publishedAt, List missingProviders, Instant staleSince)$ Freshness
    }
    class FreshnessStatus {
        <<enumeration>>
        COMPLETE
        INCOMPLETE
        STALE
    }
    class Catalog {
        <<record>>
        +List~String~ origins
        +List~String~ destinations
        +List~String~ carriers
        +List~CatalogDate~ dates
        +List~Route~ routes
    }
    class CatalogDate {
        <<record>>
        +LocalDate date
        +Freshness freshness
    }
    class Route {
        <<record>>
        +String origin
        +List~String~ destinations
    }

    ItineraryView *-- "1..*" SegmentView
    ItineraryView *-- Freshness
    Freshness --> FreshnessStatus
    Catalog *-- "0..*" CatalogDate
    Catalog *-- "0..*" Route
    CatalogDate *-- Freshness
```

**Interactions with other packages**

- → `aggregation.domain.model`: `AirportCode` and `AirlineCode` validate the filter codes.
- `InvalidSearchRequestException` extends `DomainValidationException` (`shared.domain`) → `400` naming the parameter.
- ← used by the use cases and ports of [search · application](search-application.md); rows are mapped into these
  views by `ReadModelRows` and cached by `SearchCacheCodec` ([search · infrastructure](search-infrastructure.md)).
- No dependency on `ingestion`: the two features share only the PostgreSQL schema.
