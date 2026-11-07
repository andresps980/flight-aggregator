# ingestion · infrastructure · persistence, JSON and wiring

`com.andresps.flights.ingestion.infrastructure.adapter.out.persistence`, `….infrastructure.json`, `….infrastructure.config`

The PostgreSQL side of ingestion (spec 004) and the composition root of the feature. **Persistence** implements four
driven ports: each day is replaced in one transaction (delete its rows, bulk load the new ones with `COPY`, upsert
`day_snapshot`, bump `data_version`) ([ADR-0017](../../../adr/0017-day-snapshot-replacement.md),
[ADR-0018](../../../adr/0018-bulk-load.md)); runs are stored with their report as JSONB; the run lock is a session
advisory lock on a dedicated, non-pooled connection ([ADR-0019](../../../adr/0019-ingestion-run-lock.md)). It is the
only place with PostgreSQL-specific code (ArchUnit). **JSON** maps the report for both storage and the API.
**Config** builds every adapter and use case as a bean.

## persistence

```mermaid
classDiagram
    %% package: com.andresps.flights.ingestion.infrastructure.adapter.out.persistence
    class PostgresDaySnapshotStore {
        +withCopy(DataSource dataSource)$ PostgresDaySnapshotStore
        +find(LocalDate day) Optional~DaySnapshot~
        +replace(DaySnapshotWrite write)
        +markStale(LocalDate day, UUID runId, Instant at)
        +deleteDaysBefore(LocalDate day)
        +refreshStatistics()
    }
    class ItineraryBulkLoader {
        <<interface>>
        load(Connection connection, DaySnapshotWrite write) long
    }
    class CopyItineraryLoader {
        load(Connection connection, DaySnapshotWrite write) long
    }
    class ItineraryCopyEncoder {
        COPY text format, 64 KiB buffer
        text(String value)
        arrayLiteral(Collection elements)
    }
    class SegmentsJson {
        of(List segments)$ String
    }
    class PostgresIngestionRunRepository {
        +create(IngestionRun run)
        +update(IngestionRun run)
        +findLatest() Optional~IngestionRun~
        +abortRunning(Instant at) int
    }
    class PostgresAdvisoryRunLock {
        +connectingTo(String url, String username, String password)$ PostgresAdvisoryRunLock
        +tryAcquire() Optional~RunLease~
    }

    PostgresDaySnapshotStore o-- ItineraryBulkLoader
    CopyItineraryLoader ..|> ItineraryBulkLoader
    CopyItineraryLoader --> ItineraryCopyEncoder
    CopyItineraryLoader ..> SegmentsJson
```

| Adapter | Implements (`ingestion.application.port.out`) |
|---|---|
| `PostgresDaySnapshotStore` | `DaySnapshotStore`, `SnapshotMaintenance` (one adapter, two segregated ports) |
| `PostgresIngestionRunRepository` | `IngestionRunRepository` |
| `PostgresAdvisoryRunLock` | `IngestionRunLock`; its private lease implements `RunLease` |

`ItineraryBulkLoader` keeps the load technique replaceable: the batched-`INSERT` alternative lives in the tests, for
the benchmark that chose `COPY`.

## json

```mermaid
classDiagram
    %% package: com.andresps.flights.ingestion.infrastructure.json
    class IngestionReportJson {
        +write(IngestionReport report)$ String
        +read(String json)$ IngestionReport
    }
```

Its nested records (`ReportDocument`, `ProviderDocument`, `FailureDocument`, `DayDocument`, `StatisticsDocument`) are
the JSON shape of the report, stored in `ingestion_run.report` and embedded as is in `IngestionRunResponse`.

## config

```mermaid
classDiagram
    %% package: com.andresps.flights.ingestion.infrastructure.config
    class IngestionConfiguration {
        composition root of the feature
    }
    class AggregatorProperties {
        <<record>>
        +Map providers
        +IngestionProperties ingestion
        +ResilienceProperties resilience
        +PersistenceProperties persistence
        +resilienceFor(String providerId) ResilienceSettings
        +ingestionSettings() IngestionSettings
    }
    class IngestionRunExecutor {
        virtual thread per run
        +execute(Runnable task)
        +close()
    }

    IngestionConfiguration ..> AggregatorProperties : binds aggregator.*
    IngestionConfiguration ..> IngestionRunExecutor : creates
```

**Interactions with other packages**

- ← the ports above are called by `IngestionRunService` and `DaySnapshotPublisher` ([ingestion · application](ingestion-application.md)).
- → PostgreSQL through `DataSource` / `JdbcClient`; `COPY` through PgJDBC's `PGCopyOutputStream`. Writes `itinerary`,
  `day_snapshot`, `ingestion_run` and `data_version`, which `search` reads ([schema](../../../architecture.md#schema)).
- `IngestionConfiguration` builds the three parsers, a breaker registry and one `HttpProviderFeed` per provider
  ([provider adapters](ingestion-infrastructure-out-provider.md)), the persistence adapters, the four use cases with
  a `PercentageMarkupPolicy` and `SnapshotPolicy`, the scheduler (started on `ApplicationReadyEvent`, stopped on
  `ContextClosedEvent`) and the controller's dependencies.
- `IngestionReportJson` ← used by `PostgresIngestionRunRepository` and by `IngestionRunResponse`
  ([adapters in](ingestion-infrastructure-in.md)).
