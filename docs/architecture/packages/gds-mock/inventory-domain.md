# gds-mock · inventory · domain

`com.andresps.gdsmock.inventory.domain.generator`, `….domain.model`, `….domain.stats`

The mock's world (spec 002, [ADR-0013](../../../adr/0013-stateless-seeded-streamed-generation.md)). `WorldGenerator`
produces itinerary *i* of day *d* from its own seeded `SplittableRandom`, lazily and on demand, so a provider or a day
is generated on its own, every provider sees the same itinerary, and nothing is ever materialised. Each world
itinerary carries the provider records that offer it — cross- and same-provider duplicates at different prices, one
invalid kind per spec 001 discard reason, malformed records. `WorldStatisticsCalculator` reports what the world
contains and what the aggregator should produce from it, for exact reconciliation. Spec 008: `ProviderCurrencies`
prices each record in a currency drawn from its provider's list (`FeedCurrency`: code + units per EUR) with a seed of
its own, derived from the itinerary's seed, the provider and the copy, so the world never changes with the currencies.
Plain Java.

## generator and model

```mermaid
classDiagram
    %% package: com.andresps.gdsmock.inventory.domain.generator
    %% package: com.andresps.gdsmock.inventory.domain.model
    class WorldGenerator {
        +horizon() Horizon
        +itineraries(LocalDate day) Stream~WorldItinerary~
        +records(ProviderId provider, LocalDate day) Stream~ProviderRecord~
        +providers() List~ProviderId~
    }
    class ProviderCurrencies {
        +of(Map byProvider)$ ProviderCurrencies
        +eurOnly()$ ProviderCurrencies
        +currencies(ProviderId provider) List~FeedCurrency~
    }
    class FeedCurrency {
        <<record>>
        +Currency currency
        +BigDecimal unitsPerEur
        +fromEur(BigDecimal eur) BigDecimal
    }
    class FxRates {
        <<record>>
        +Map unitsPerEur
        +byCode() Map
    }
    class WorldSettings {
        <<record>>
        +long seed
        +int volume
        +int horizonDays
        +LocalDate referenceDate
        +double invalidFraction
        +double malformedFraction
        +double sameProviderDuplicateFraction
    }
    class ScheduleFactory
    class OfferFactory
    class Catalog {
        airports with IANA zones, airlines
    }
    class DateOutsideHorizonException
    class Horizon {
        <<record>>
        +LocalDate referenceDate
        +LocalDate from
        +LocalDate to
        +contains(LocalDate day) boolean
        +days() List~LocalDate~
    }
    class WorldItinerary {
        <<record>>
        +LocalDate partitionDay
        +int index
        +List~ScheduledSegment~ segments
        +BigDecimal baseFare
        +Optional~InvalidKind~ invalidKind
        +List~ProviderRecord~ records
    }
    class ProviderRecord {
        <<record>>
        +ProviderId provider
        +String recordId
        +int copy
        +List~ScheduledSegment~ segments
        +BigDecimal price
        +Currency currency
        +Optional~Malformation~ malformation
        +pricedIn(FeedCurrency target) ProviderRecord
    }
    class ScheduledSegment {
        <<record>>
        +String airline
        +int flightNumber
        +Airport origin
        +Airport destination
        +Instant departure
        +Instant arrival
    }
    class Airport {
        <<record>>
        +String code
        +ZoneId zone
    }
    class Malformation {
        <<record>>
        +MalformationKind kind
        +int segmentIndex
    }
    class InvalidKind {
        <<enumeration>>
        MIXED_AIRLINES … ALREADY_DEPARTED
    }
    class MalformationKind {
        <<enumeration>>
        BAD_AIRPORT_CODE
        NON_POSITIVE_PRICE
        UNPARSEABLE_TIME
    }

    WorldGenerator --> WorldSettings
    WorldGenerator --> ProviderCurrencies
    ProviderCurrencies *-- "1..*" FeedCurrency
    WorldGenerator *-- ScheduleFactory
    WorldGenerator *-- OfferFactory
    ScheduleFactory ..> Catalog
    WorldGenerator ..> Horizon
    WorldGenerator ..> DateOutsideHorizonException : throws
    WorldGenerator ..> WorldItinerary : generates
    WorldItinerary *-- "1..*" ScheduledSegment
    WorldItinerary *-- "0..*" ProviderRecord
    WorldItinerary --> InvalidKind
    ProviderRecord *-- "1..*" ScheduledSegment
    ProviderRecord o-- Malformation
    Malformation --> MalformationKind
    ScheduledSegment *-- "2" Airport
```

`InvalidKind` mirrors the aggregator's seven `DiscardReason`s by name (the two modules share no code).

## stats

```mermaid
classDiagram
    %% package: com.andresps.gdsmock.inventory.domain.stats
    class WorldStatisticsCalculator {
        +calculate(Stream~WorldItinerary~ itineraries) WorldStatistics
    }
    class WorldStatistics {
        <<record>>
        +long distinctItineraries
        +Map invalidItineraries
        +long crossProviderDuplicates
        +Map~ProviderId, ProviderStatistics~ providers
        +FullyMalformedItineraries fullyMalformedItineraries
        +ExpectedAggregation expectedAggregation
    }
    class ProviderStatistics {
        <<record>>
        +long records
        +long sameProviderDuplicates
        +long fullyMalformedItineraries
        +Map malformed
    }
    class FullyMalformedItineraries {
        <<record>>
        +long total
        +Map byInvalidKind
    }
    class ExpectedAggregation {
        <<record>>
        +Map offersReceived
        +Map discarded
        +long duplicatesMerged
        +long itinerariesProduced
    }

    WorldStatisticsCalculator ..> WorldStatistics : produces
    WorldStatistics *-- "1..*" ProviderStatistics
    WorldStatistics *-- FullyMalformedItineraries
    WorldStatistics *-- ExpectedAggregation
```

**Interactions with other packages**

- → `gdsmock.shared.domain`: `ProviderId`; `DateOutsideHorizonException` extends `InvalidRequestException` (→ `400`).
- ← `ProviderFeedController` streams `records(provider, day)` through a `ProviderFormat`, and `WorldStatsController`
  serves `WorldStatisticsCalculator`'s result through `WorldStatisticsCache`
  ([inventory · infrastructure](inventory-infrastructure.md)).
- ← The aggregator's e2e test (`IngestionReconciliationE2EIT`) compares its ingestion report with these statistics over
  HTTP; there is no code dependency.
