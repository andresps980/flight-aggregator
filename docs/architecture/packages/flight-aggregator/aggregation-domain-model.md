# aggregation · domain · model

`com.andresps.flights.aggregation.domain.model`

The value objects of the core domain (spec 001, [ADR-0010](../../../adr/0010-domain-assumptions.md)). Each record
validates its invariants in its constructor, so a malformed provider value (a 2-letter airport, a non-positive price,
an amount out of bounds) fails here, before it can reach the aggregator. `Money` is EUR only: `ExchangeRates` (spec 008,
[ADR-0025](../../../adr/0025-currency-normalisation-live-rates.md)) converts a provider amount in any currency to EUR
with the run's rates, before the offer is built. `Itinerary` derives the deduplication identity
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
        +requireBounded(BigDecimal amount)$
        +isPositive() boolean
    }
    class ExchangeRates {
        +of(LocalDate date, Map rates)$ ExchangeRates
        +unavailable()$ ExchangeRates
        +available() boolean
        +date() Optional~LocalDate~
        +toEur(BigDecimal amount, Currency currency) Money
    }
    class ExchangeRatesUnavailableException
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
    ExchangeRates ..> Money : converts to
    ExchangeRates ..> InvalidFlightDataException : unknown currency
    ExchangeRates ..> ExchangeRatesUnavailableException : no rates
```

**Interactions with other packages**

- `InvalidFlightDataException` extends `DomainValidationException` (`shared.domain`), which the REST layer maps to
  `400`; during ingestion the provider adapters catch it and count the record as malformed.
- ← built by the provider parsers (`ingestion.infrastructure.adapter.out.provider`, through `RecordValues`), which
  convert every price with `ExchangeRates`; the rates come from the `ExchangeRateSource` port
  ([ingestion · application](ingestion-application.md)), once per run.
- ← consumed by [validation](aggregation-domain-validation.md) (`Itinerary`), [pricing](aggregation-domain-pricing-and-deduplication.md)
  (`Money`) and deduplication (`FlightOffer`, `ItineraryKey`, `ProviderId`).
- ← `search.domain` reuses `AirportCode` and `AirlineCode` to validate filters.
