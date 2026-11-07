# aggregation · domain · validation

`com.andresps.flights.aggregation.domain.validation`

Filters out well-formed but unsellable itineraries: dead legs and invalid connections (spec 001). Each rule is one
class behind the one-method `ItineraryRule`; `ItineraryValidator` evaluates them in a fixed order and returns the
first violated rule's `DiscardReason`, so every discarded itinerary is counted under exactly one reason. A new rule is
a new class plus a `DiscardReason` constant. Plain Java; "now" comes from an injected `Clock`.

```mermaid
classDiagram
    %% package: com.andresps.flights.aggregation.domain.validation
    class ItineraryRule {
        <<interface>>
        +check(Itinerary itinerary) Optional~DiscardReason~
    }
    class ItineraryValidator {
        +withDefaultRules(Clock clock)$ ItineraryValidator
        +validate(Itinerary itinerary) Optional~DiscardReason~
    }
    class DiscardReason {
        <<enumeration>>
        MIXED_AIRLINES
        ARRIVAL_NOT_AFTER_DEPARTURE
        SEGMENTS_NOT_CHAINED
        REVISITED_AIRPORT
        CONNECTION_TOO_SHORT
        LAYOVER_TOO_LONG
        ALREADY_DEPARTED
    }
    class SingleAirlineRule
    class ArrivalAfterDepartureRule
    class ChainedSegmentsRule
    class NoRevisitedAirportRule
    class MinimumConnectionTimeRule {
        +DEFAULT_MINIMUM$ Duration
    }
    class MaximumLayoverRule {
        +DEFAULT_MAXIMUM$ Duration
    }
    class NotDepartedRule

    ItineraryValidator o-- "1..*" ItineraryRule : evaluates in order
    ItineraryRule ..> DiscardReason : returns
    SingleAirlineRule ..|> ItineraryRule
    ArrivalAfterDepartureRule ..|> ItineraryRule
    ChainedSegmentsRule ..|> ItineraryRule
    NoRevisitedAirportRule ..|> ItineraryRule
    MinimumConnectionTimeRule ..|> ItineraryRule
    MaximumLayoverRule ..|> ItineraryRule
    NotDepartedRule ..|> ItineraryRule
```

Default order (`withDefaultRules`): structural (single airline, arrival after departure, chained segments, no
revisited airport), then timing (connection ≥ 45 min, layover ≤ 24 h), then time-relative (not departed, against the
clock read once when the validator is built).

**Interactions with other packages**

- → reads `Itinerary` and its derived facts ([model](aggregation-domain-model.md)).
- ← `OfferAggregator` ([deduplication](aggregation-domain-pricing-and-deduplication.md)) calls `validate` for every
  offer; `DiscardReason` is the key of its discard statistics.
- ← `IngestionService` (`ingestion.application.service`) builds one validator per run with `withDefaultRules`.
