# ingestion · application

`com.andresps.flights.ingestion.application.port.in`, `….port.out`, `….service`

The ingestion use cases and the ports they own. **Driving ports** (`port.in`) are what the scheduler and the REST
controller call: start a run, read runs. **Driven ports** (`port.out`) are what the use cases need from the outside:
provider feeds, the day sink, the run lock, the run repository, the snapshot store. The **services** implement the
ports in plain Java, without Spring annotations; they are wired as beans in `ingestion.infrastructure.config`
([ADR-0002](../../../adr/0002-hexagonal-architecture.md)). Start here and follow the ports outwards.

## Driving ports (`port.in`)

```mermaid
classDiagram
    %% package: com.andresps.flights.ingestion.application.port.in
    class StartIngestionUseCase {
        <<interface>>
        +start(IngestionTrigger trigger) StartedIngestion
    }
    class GetIngestionRunUseCase {
        <<interface>>
        +byId(UUID id) Optional~IngestionRun~
        +latest() Optional~IngestionRun~
    }
    class IngestFlightsUseCase {
        <<interface>>
        +ingest(UUID runId, PublicationGuard guard) IngestionReport
    }
    class PublicationGuard {
        <<interface>>
        +mayPublish() boolean
    }
    class StartedIngestion {
        <<record>>
        +IngestionRun run
        +CompletableFuture~IngestionRun~ completion
    }
    class IngestionAlreadyRunningException {
        +runningId() Optional~UUID~
    }
    class RunLockLostException

    StartIngestionUseCase ..> StartedIngestion : returns
    StartIngestionUseCase ..> IngestionAlreadyRunningException : throws
    IngestFlightsUseCase ..> PublicationGuard : checks before each day
    IngestFlightsUseCase ..> RunLockLostException : throws
```

## Driven ports (`port.out`)

```mermaid
classDiagram
    %% package: com.andresps.flights.ingestion.application.port.out
    class ProviderFeed {
        <<interface>>
        +provider() ProviderId
        +fetch(LocalDate day, ExchangeRates rates) ProviderPartition
        +circuitState() CircuitState
    }
    class PartitionFetchException {
        +kind() FailureKind
        +attempts() int
    }
    class DayAggregationSink {
        <<interface>>
        +publish(DayAggregation day) DayPublication
        +unavailable(UUID runId, LocalDate day) DayPublication
    }
    class DaySnapshotStore {
        <<interface>>
        +find(LocalDate day) Optional~DaySnapshot~
        +replace(DaySnapshotWrite write)
        +markStale(LocalDate day, UUID runId, Instant at)
    }
    class DaySnapshotWrite {
        <<record>>
        +LocalDate day
        +UUID runId
        +List~AggregatedItinerary~ itineraries
        +SortedSet providers
        +SortedSet missing
        +Instant publishedAt
        +complete() boolean
    }
    class SnapshotMaintenance {
        <<interface>>
        +deleteDaysBefore(LocalDate day)
        +refreshStatistics()
    }
    class IngestionRunLock {
        <<interface>>
        +tryAcquire() Optional~RunLease~
    }
    class RunLease {
        <<interface>>
        +isHeld() boolean
        +close()
    }
    class IngestionRunRepository {
        <<interface>>
        +create(IngestionRun run)
        +update(IngestionRun run)
        +findById(UUID id) Optional~IngestionRun~
        +findLatest() Optional~IngestionRun~
        +findRunning() Optional~UUID~
        +abortRunning(Instant at) int
        +deleteStartedBefore(Instant instant) int
    }

    class ExchangeRateSource {
        <<interface>>
        +latest() ExchangeRates
    }

    ProviderFeed ..> PartitionFetchException : throws
    DaySnapshotStore ..> DaySnapshotWrite
    IngestionRunLock ..> RunLease : returns
```

## Use cases (`service`)

```mermaid
classDiagram
    %% package: com.andresps.flights.ingestion.application.service
    class IngestionRunService {
        +start(IngestionTrigger trigger) StartedIngestion
    }
    class IngestionService {
        +ingest(UUID runId, PublicationGuard guard) IngestionReport
    }
    class IngestionSettings {
        <<record>>
        +int horizonDays
        +int maxConcurrentDays
        +int maxConcurrentPartitions
        +Duration runTimeout
    }
    class DaySnapshotPublisher {
        +publish(DayAggregation day) DayPublication
        +unavailable(UUID runId, LocalDate day) DayPublication
    }
    class IngestionRunQueryService {
        +byId(UUID id) Optional~IngestionRun~
        +latest() Optional~IngestionRun~
    }

    IngestionService --> IngestionSettings
```

What each service implements and uses (ports from the diagrams above, not redrawn across packages):

| Service | Implements | Uses |
|---|---|---|
| `IngestionRunService` | `StartIngestionUseCase` | `IngestFlightsUseCase` (on an injected `Executor`), `IngestionRunLock`, `IngestionRunRepository`, `SnapshotMaintenance`, `Clock` |
| `IngestionService` | `IngestFlightsUseCase` | `ExchangeRateSource` (once per run; on failure `ExchangeRates.unavailable()`, spec 008), `ProviderFeed` × 3 (each fetch gets the run's rates), `DayAggregationSink`, `MarkupPolicy`, `Clock`, `IngestionSettings`; builds an `ItineraryValidator` and one `OfferAggregator` per day |
| `DaySnapshotPublisher` | `DayAggregationSink` | `DaySnapshotStore`, `SnapshotPolicy`, `Clock` |
| `IngestionRunQueryService` | `GetIngestionRunUseCase` | `IngestionRunRepository` |

**Interactions with other packages**

- ← `IngestionScheduler` and `IngestionController` ([adapters in](ingestion-infrastructure-in.md)) call
  `StartIngestionUseCase`; the controller also calls `GetIngestionRunUseCase`.
- → `ProviderFeed` is implemented by `HttpProviderFeed` and `ExchangeRateSource` by `FrankfurterExchangeRateSource`
  ([provider and FX adapters](ingestion-infrastructure-out-provider.md));
  `IngestionRunLock`, `IngestionRunRepository`, `DaySnapshotStore` and `SnapshotMaintenance` by the
  [PostgreSQL adapters](ingestion-infrastructure-out-persistence.md).
- → `aggregation.domain` (`OfferAggregator`, `ItineraryValidator`, `MarkupPolicy`) does the deduplication;
  `ingestion.domain` supplies the report, run and snapshot types and `SnapshotPolicy`.
- `IngestionAlreadyRunningException` extends `ConflictException` (`shared.domain`) → `409` with `runningIngestionId`.
- The runtime sequence is in [flows.md](../../flows.md#ingestion-run).
