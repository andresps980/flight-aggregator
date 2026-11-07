# ingestion · domain

`com.andresps.flights.ingestion.domain`

The vocabulary of an ingestion run (specs 003 and 004): what a provider delivered for one day (`ProviderPartition`),
how a run went (`IngestionReport` per provider and per day, statuses, failure kinds, malformed-record reasons), the
run's lifecycle (`IngestionRun`) and the rule that decides whether a day's new aggregation may replace the stored
snapshot (`SnapshotPolicy`, [ADR-0017](../../../adr/0017-day-snapshot-replacement.md)). Plain Java, no I/O. Split
into two diagrams: the report, and the run with its day snapshots.

## Report and partitions

```mermaid
classDiagram
    %% package: com.andresps.flights.ingestion.domain
    class IngestionReport {
        <<record>>
        +UUID runId
        +Instant startedAt
        +Instant finishedAt
        +List~ProviderReport~ providers
        +List~DayReport~ days
        +AggregationStatistics statistics
        +status() IngestionStatus
        +storedItineraries() long
    }
    class ProviderReport {
        <<record>>
        +ProviderId provider
        +int partitionsRequested
        +int partitionsSucceeded
        +List~PartitionFailure~ failures
        +int attempts
        +CircuitState circuitState
        +Map malformed
        +status() IngestionStatus
    }
    class PartitionFailure {
        <<record>>
        +LocalDate day
        +FailureKind kind
        +int attempts
        +String detail
    }
    class DayReport {
        <<record>>
        +LocalDate day
        +SortedSet contributing
        +SortedSet missing
        +DayPublication publication
        +AggregationStatistics statistics
    }
    class ProviderPartition {
        <<record>>
        +ProviderId provider
        +LocalDate day
        +List~FlightOffer~ offers
        +Map malformed
        +int attempts
    }
    class IngestionStatus {
        <<enumeration>>
        COMPLETED
        PARTIAL
        FAILED
        +ofPartitions(int succeeded, int failed)$ IngestionStatus
        +overall(Collection providers)$ IngestionStatus
    }
    class FailureKind {
        <<enumeration>>
        TIMEOUT
        SERVER_ERROR
        CLIENT_ERROR
        IO_ERROR
        MALFORMED_DOCUMENT
        CIRCUIT_OPEN
        RUN_TIMEOUT
        UNEXPECTED_ERROR
    }
    class MalformedReason {
        <<enumeration>>
        BAD_AIRPORT_CODE
        BAD_FLIGHT_NUMBER
        NON_POSITIVE_PRICE
        UNPARSEABLE_TIME
        INVALID_RECORD
    }
    class CircuitState {
        <<enumeration>>
        CLOSED
        OPEN
        HALF_OPEN
    }
    class MalformedCounts {
        +nonZero(Map counts)$ Map
        +total(Map counts)$ long
    }

    IngestionReport *-- "1..*" ProviderReport
    IngestionReport *-- "0..*" DayReport
    ProviderReport *-- "0..*" PartitionFailure
    ProviderReport --> CircuitState
    ProviderReport ..> IngestionStatus
    PartitionFailure --> FailureKind
    ProviderReport ..> MalformedReason
    ProviderPartition ..> MalformedReason
    ProviderReport ..> MalformedCounts
    ProviderPartition ..> MalformedCounts
```

## Run and day snapshots

```mermaid
classDiagram
    %% package: com.andresps.flights.ingestion.domain
    class IngestionRun {
        <<record>>
        +UUID id
        +IngestionTrigger trigger
        +RunStatus status
        +Instant startedAt
        +Instant finishedAt
        +IngestionReport report
        +String failure
        +started(UUID id, IngestionTrigger trigger, Instant at)$ IngestionRun
        +finished(IngestionReport report, Instant at) IngestionRun
        +failed(String message, Instant at) IngestionRun
        +aborted(Instant at) IngestionRun
    }
    class IngestionReport {
        <<record>>
    }
    class IngestionTrigger {
        <<enumeration>>
        SCHEDULED
        MANUAL
    }
    class RunStatus {
        <<enumeration>>
        RUNNING
        COMPLETED
        PARTIAL
        FAILED
        ABORTED
        +of(IngestionStatus status)$ RunStatus
    }
    class DayAggregation {
        <<record>>
        +UUID runId
        +LocalDate day
        +AggregationResult result
        +SortedSet contributing
        +SortedSet missing
    }
    class DaySnapshot {
        <<record>>
        +LocalDate day
        +UUID runId
        +SortedSet providers
        +Instant publishedAt
        +Instant staleSince
        +stale() boolean
    }
    class SnapshotPolicy {
        +SnapshotPolicy(Duration maxStaleAge)
        +decide(Optional~DaySnapshot~ existing, SortedSet contributing, SortedSet missing, Instant now) SnapshotDecision
    }
    class SnapshotDecision {
        <<enumeration>>
        REPLACE
        KEEP_STALE
    }
    class DayPublication {
        <<record>>
        +DayOutcome outcome
        +int storedItineraries
        +int misfiledItineraries
    }
    class DayOutcome {
        <<enumeration>>
        REPLACED
        PUBLISHED_INCOMPLETE
        KEPT_STALE
        NO_DATA
        WRITE_FAILED
    }

    IngestionRun --> IngestionTrigger
    IngestionRun --> RunStatus
    IngestionRun o-- "0..1" IngestionReport
    SnapshotPolicy ..> DaySnapshot : reads
    SnapshotPolicy ..> SnapshotDecision : returns
    DayPublication --> DayOutcome
```

`SnapshotPolicy.decide` replaces a day when no provider is missing, when nothing is stored yet, when the contributors
cover every provider of the stored snapshot, or when the stored snapshot has been stale for `max-stale-age` (2 h);
otherwise it keeps the stored snapshot and marks it stale. A run is `FAILED` only if every provider failed.

**Interactions with other packages**

- → `aggregation.domain`: partitions carry `FlightOffer`s, `DayAggregation` carries an `AggregationResult`, reports
  carry `AggregationStatistics`, and providers are `ProviderId`s.
- ← built and consumed by the use cases in [ingestion · application](ingestion-application.md): `IngestionService`
  assembles the report, `DaySnapshotPublisher` applies `SnapshotPolicy` and returns `DayPublication`s,
  `IngestionRunService` moves `IngestionRun` through its states.
- ← `ProviderPartition` is produced by `PartitionBuffer` in the provider adapters; `IngestionRun` / `IngestionReport`
  are stored by the persistence adapter and served by the REST adapter (both through `IngestionReportJson`).
