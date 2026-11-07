# aggregation · domain · model

`com.andresps.flights.aggregation.domain.model`

The value objects of the core domain (spec 001, [ADR-0010](../../../adr/0010-domain-assumptions.md)). Each record
validates its invariants in its constructor, so a malformed provider value (a 2-letter airport, a non-positive or
non-EUR price) fails here, before it can reach the aggregator. `Itinerary` derives the deduplication identity
(`ItineraryKey`: flight numbers, airports and departure instants) and the facts the validation rules need. Plain Java.

```mermaid
classDiagram
    %% package: com.andresps.flights.aggregation.domain.model
    class FlightOffer {
        <<record>>
        +Itinerary itinerary
        +ProviderId provider
        +Money basePrice
    }
    class Itinerary {
        <<record>>
        +List~Segment~ segments
        +key() ItineraryKey
        +canonicalForm() String
        +firstDeparture() Instant
        +connectionTimes() List~Duration~
        +visitedAirports() List~AirportCode~
    }
    class Segment {
        <<record>>
        +FlightNumber flightNumber
        +AirportCode origin
        +AirportCode destination
        +Instant departure
        +Instant arrival
        +airline() AirlineCode
    }
    class FlightNumber {
        <<record>>
        +AirlineCode airline
        +String number
        +parse(String raw)$ FlightNumber
    }
    class AirportCode {
        <<record>>
        +String value
    }
    class AirlineCode {
        <<record>>
        +String value
    }
    class ItineraryKey {
        <<record>>
        +String value
    }
    class ProviderId {
        <<record>>
        +String value
    }
    class Money {
        <<record>>
        +BigDecimal amount
        +Currency currency
        +eur(BigDecimal amount)$ Money
        +isPositive() boolean
    }
    class InvalidFlightDataException {
        +requireField(T value, String field)$ T
        +normaliseCode(String raw, String field)$ String
    }

    FlightOffer *-- Itinerary
    FlightOffer *-- ProviderId
    FlightOffer *-- Money
    Itinerary *-- "1..*" Segment
    Itinerary ..> ItineraryKey : derives
    Segment *-- FlightNumber
    Segment *-- "2" AirportCode
    FlightNumber *-- AirlineCode
    AirportCode ..> InvalidFlightDataException : throws
    Money ..> InvalidFlightDataException : throws
```

**Interactions with other packages**

- `InvalidFlightDataException` extends `DomainValidationException` (`shared.domain`), which the REST layer maps to
  `400`; during ingestion the provider adapters catch it and count the record as malformed.
- ← built by the provider parsers (`ingestion.infrastructure.adapter.out.provider`, through `RecordValues`).
- ← consumed by [validation](aggregation-domain-validation.md) (`Itinerary`), [pricing](aggregation-domain-pricing-and-deduplication.md)
  (`Money`) and deduplication (`FlightOffer`, `ItineraryKey`, `ProviderId`).
- ← `search.domain` reuses `AirportCode` and `AirlineCode` to validate filters.
