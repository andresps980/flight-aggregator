# Spec 004: Persistence and the end-to-end ingestion pipeline

- **Status:** Implemented
- **Branch:** feat/004-persistence-and-pipeline
- **Related ADRs:** [ADR-0006](../../docs/adr/0006-scheduled-pre-ingestion.md),
  [ADR-0007](../../docs/adr/0007-postgresql-and-redis.md), [ADR-0008](../../docs/adr/0008-keyset-pagination.md),
  [ADR-0016](../../docs/adr/0016-day-partitioned-aggregation.md),
  [ADR-0017](../../docs/adr/0017-day-snapshot-replacement.md), [ADR-0018](../../docs/adr/0018-bulk-load.md),
  [ADR-0019](../../docs/adr/0019-ingestion-run-lock.md)

## Context

From the [statement](../../docs/exercise.md): the system must *"handle an inventory profile ranging from 100,000 to
1,000,000 flight combinations daily"*, serve a search with *"high-performance filtering … using an optimized
pagination strategy"*, justify the *"data structure modeling … up to 1,000,000 rows"*, and boot with
`docker compose up --build`.

Spec 003 delivered the ingestion use case, which publishes one day at a time to `DayAggregationSink`, backed by an
in-memory store that holds the whole world (≈ 1–1.2 GB at 1M) and has no trigger. This spec replaces the store with
PostgreSQL, designed as the **read model of the search** (next spec), triggers ingestion on a schedule and on demand
over REST (ADR-0006), persists every run and its report, and adds a first `docker-compose.yml` with PostgreSQL,
gds-mock and flight-aggregator.

It also honours spec 003's *Requirements for the persistence spec*: a day published with missing providers must not
silently wipe their previous good data, and the container heap is revisited.

## Definitions

- **Day** — a horizon day (spec 003): the UTC date of an itinerary's first departure, which is also the provider
  partition it came from.
- **Snapshot of a day** — the set of itinerary rows of that day, all written by the same run in one transaction.
- **Complete / incomplete snapshot** — written from every configured provider / with at least one missing.
- **Stale snapshot** — a snapshot that the latest run that covered its day did not replace (that run's data for the
  day was less complete, or every provider failed for it). Stale data is kept, never silently wiped.

## Scope

**In scope**
- Flyway migrations: itinerary read model, day status, ingestion runs.
- A PostgreSQL adapter for `DayAggregationSink` with day snapshot semantics, bulk load and retention.
- Persisted ingestion runs (status, timestamps, full report, per-day outcome) and a port to read them.
- Triggers: a scheduler (initial run shortly after startup, then a fixed delay) and `POST /api/v1/ingestions`;
  `GET /api/v1/ingestions/{id}` and `GET /api/v1/ingestions/latest`.
- No concurrent runs, also across instances (database lock).
- `docker-compose.yml` with `postgres`, `gds-mock` and `flight-aggregator`; a measured new heap for the aggregator.
- Measurements: bulk load time for 1M rows (COPY vs batched inserts), a full 1M run (time, heap).

**Out of scope**
- The search API, its final indexes and cursor format (next spec; the schema is shaped for it here).
- Redis, the frontend, CI smoke job against compose (later PRs).
- Authentication of the ingestion endpoints (listed as a limitation).
- Cancelling a running ingestion, listing all runs (only by id and latest).

## Data model

One **denormalized row per aggregated itinerary**, so a search page is a single index range scan with no joins.

`itinerary`

| Column | Type | Notes |
|---|---|---|
| `id` | `bigint generated always as identity` PK | Keyset tie-breaker with price (ADR-0008); new ids on every snapshot |
| `itinerary_key` | `text` **unique** | Spec 001 `ItineraryKey`; globally unique because it contains departure instants |
| `departure_date` | `date` not null | UTC date of the first departure = the day; snapshot and retention unit |
| `origin`, `destination` | `char(3)` not null | First origin, last destination |
| `departure_at`, `arrival_at` | `timestamptz` not null | First departure, last arrival |
| `airline` | `char(2)` not null | Single-airline itineraries (ADR-0010) |
| `stops` | `smallint` not null | Segments − 1 |
| `base_price`, `final_price` | `numeric(12,2)` not null | EUR (ADR-0010); `final_price` = base + 5 % markup, the price users filter and sort on |
| `selected_provider` | `text` not null | Provider of the selected (cheapest) offer |
| `providers` | `text[]` not null | Every provider that offered it, sorted |
| `segments` | `jsonb` not null | Ordered `[{flightNumber, origin, destination, departureAt, arrivalAt}]`, returned as is by search |
| `run_id` | `uuid` not null | Run that wrote the snapshot |
| `created_at` | `timestamptz` not null | When the row was written |

Why this shape (search indexes finalized in the search spec, with load-time measurements):
- Every search filter (origin, destination, max price, carrier) and the sort key are **plain scalar columns** of the
  same row, so a composite B-tree such as `(origin, destination, final_price, id)` can serve filter + keyset order
  with an index range scan and `LIMIT`; carrier is a cheap residual filter or a second index. Segments are only
  displayed, never filtered, so JSONB avoids a `segment` table and a join (and its 2–3× row count) on every read.
- `departure_date` makes a day's replacement and the retention a single indexed `DELETE`, and lets search exclude
  past days cheaply.
- This PR creates only the PK, the unique key and an index on `departure_date` (needed by the writes). Each extra
  index slows the bulk load; the search spec adds and measures its own.
- Estimated size: ≈ 0.5–0.7 KB per row ⇒ ≈ 0.6 GB with indexes at 1M rows.

`day_snapshot` (one row per day that has a snapshot): `departure_date` PK, `run_id` (snapshot's run), `published_at`,
`providers text[]`, `missing_providers text[]`, `complete boolean`, `itineraries integer`, `stale_since timestamptz`
null, `last_run_id uuid` (latest run that covered the day).

`ingestion_run`: `id uuid` PK, `trigger` (`SCHEDULED` | `MANUAL`), `status` (`RUNNING` | `COMPLETED` | `PARTIAL` |
`FAILED` | `ABORTED`), `started_at`, `finished_at` null, `report jsonb` null (spec 003 report + per-day outcome,
written when the run finishes), `failure text` null (error of a `FAILED` / lock-lost run); index on `started_at` for
`latest`.

## Acceptance criteria

**Schema**
- **AC-1** — Given an empty database, when the application starts, then Flyway applies the migrations and the three
  tables above exist with their constraints; given an already migrated database, startup applies nothing.

**Day snapshot semantics** ([ADR-0017](../../docs/adr/0017-day-snapshot-replacement.md))
- **AC-2** — Given a day published with every provider contributing (even if the stored snapshot lists a provider no
  longer configured), then that day's rows are replaced by the new ones **atomically**: a concurrent reader of the day sees either the whole previous snapshot or the whole new one,
  never a mix nor an empty day; `day_snapshot` records it as complete, not stale, with the run id and row count.
- **AC-3** — Given a day that already has a snapshot, and a publication of that day whose contributing providers do
  **not** include every provider of the existing snapshot, then the existing rows are kept unchanged and the day is
  marked stale (`stale_since` = the first run that could not refresh it, kept across consecutive stale runs).
- **AC-4** — Given a day **without** a snapshot, or whose snapshot's providers are all among the new contributing
  providers, and a publication with missing providers, then it is written as the day's snapshot, marked incomplete
  with its missing providers, not stale.
- **AC-5** — Given a stale snapshot older than `max-stale-age` (its `stale_since`), and a partial publication of
  that day, then the partial publication replaces it (marked incomplete), so old prices are not served forever.
- **AC-6** — Given a horizon day for which every provider failed (not published, spec 003 AC-11), then an existing
  snapshot is kept and marked stale; a day without one stays without rows.
- **AC-7** — The run report records each day's outcome: `REPLACED`, `PUBLISHED_INCOMPLETE`, `KEPT_STALE`, `NO_DATA`
  or `WRITE_FAILED`, and its stored row count. The run status rules of spec 003 (AC-19) are unchanged, except that a
  `WRITE_FAILED` day makes a `COMPLETED` run `PARTIAL`.
- **AC-8 (error)** — Given a database failure while writing a day, then that day's transaction is rolled back (the
  previous snapshot stays intact), the day is reported as `WRITE_FAILED`, and the other days continue.
- **AC-9** — Given an aggregated itinerary whose first departure's UTC date differs from the published day (a
  provider breaking the partitioning assumption), then it is not stored, the day is still written, and the report
  counts it per day and in total (`misfiledItineraries`), so stored rows + misfiled = itineraries produced; a WARN
  is logged.

**Bulk load and round trip** ([ADR-0018](../../docs/adr/0018-bulk-load.md))
- **AC-10** — Given an aggregated day, when it is stored and read back, then every field round-trips exactly: key,
  dates and instants (UTC), airports, airline, stops, base and final prices (scale 2), selected and offering
  providers, and every segment in order.
- **AC-11** — Rows are streamed to PostgreSQL while being encoded, never materialised as one large buffer; values
  are escaped so that no field content can break the load (e.g. a delimiter, quote, backslash or newline).

**Retention and statistics**
- **AC-12** — Given rows and day snapshots for days before today (UTC, injected clock), and ingestion runs started
  more than 7 days ago, when a run starts, then they are deleted; today's and future days, and recent runs, are
  untouched.
- **AC-13** — When a run has finished writing its days, then `ANALYZE itinerary` is executed, so the planner's
  statistics follow each snapshot.

**Ingestion runs**
- **AC-14** — When a run starts, then an `ingestion_run` row is stored as `RUNNING` with its trigger and start
  instant before any provider is called; when it finishes, then its status, end instant and full report (spec 003
  AC-21 plus AC-7 and AC-9) are stored.
- **AC-15** — Given a run row still `RUNNING` whose process died (lock no longer held), when the next run acquires
  the lock, then the orphan is marked `ABORTED` with an end instant.
- **AC-16 (error)** — Given an unexpected exception in the use case, then the run is stored as `FAILED` with the
  error message, the lock is released, and the next run can start.

**No concurrent runs** ([ADR-0019](../../docs/adr/0019-ingestion-run-lock.md))
- **AC-17** — Given a run in progress in this or another instance (same database), when a scheduled tick or a
  manual trigger happens, then no second run starts: the tick is skipped (logged at INFO) and the manual trigger is
  rejected (AC-21). Given two triggers at the same time, exactly one run starts.
- **AC-18** — The lock is held on a dedicated connection for the whole run and released when the run finishes,
  fails or its process dies (no manual cleanup).
- **AC-19 (error)** — Given the lock connection is lost during a run, then before publishing its next day the run
  detects it, publishes nothing more, and is stored as `FAILED` (lock lost) — another instance may already be
  running.

**Scheduling**
- **AC-20** — Given scheduling enabled, then a first run starts `initial-delay` after the application is ready, and
  each next one `fixed-delay` after the previous scheduled run finished. Given scheduling disabled, no run starts on
  its own.

**REST API**
- **AC-21** — `POST /api/v1/ingestions` with no run in progress → `202 Accepted`, `Location:
  /api/v1/ingestions/{id}` and the run (`status: RUNNING`); the run executes asynchronously on a virtual thread.
  With a run in progress → `409` ProblemDetail including `runningIngestionId` (when known).
- **AC-22** — `GET /api/v1/ingestions/{id}` → `200` with the run; while running, `report` is `null`.
  `GET /api/v1/ingestions/latest` → the most recently started run.
- **AC-23 (error)** — Unknown id, or `latest` with no runs → `404` ProblemDetail; an id that is not a UUID → `400`
  ProblemDetail.

**End to end and Docker**
- **AC-24** — Given WireMock serving the recorded gds-mock fixtures (spec 003), when an ingestion runs, then the
  stored rows per day equal the report's itineraries produced per day, the persisted report reconciles with the
  fixtures' expected figures (spec 003 AC-22), and every day is `REPLACED` and complete.
- **AC-25** — Given `docker compose up --build`, then `postgres`, `gds-mock` and `flight-aggregator` become healthy
  (the aggregator starts after the other two are healthy), the initial run completes, rows are queryable, and data
  survives `docker compose down` / `up` (named volume). Checked by hand for this PR.

## API contract

`POST /api/v1/ingestions` (no body) → `202`, header `Location: http://host:port/api/v1/ingestions/3f2a…` (absolute,
built from the request)
```json
{ "id": "3f2a6c1e-…", "trigger": "MANUAL", "status": "RUNNING",
  "startedAt": "2026-11-06T10:00:00Z", "finishedAt": null, "durationMs": null, "failure": null, "report": null }
```
`failure` names the error type of a `FAILED` / `ABORTED` run (never the exception message, which may carry SQL or
hosts); `null` otherwise.
`409`:
```json
{ "type": "about:blank", "title": "Ingestion already running", "status": 409,
  "detail": "Ingestion 9b1d… is in progress", "instance": "/api/v1/ingestions", "runningIngestionId": "9b1d…" }
```
`GET /api/v1/ingestions/{id}` and `GET /api/v1/ingestions/latest` → `200`, same shape; once finished, `status` is
`COMPLETED` | `PARTIAL` | `FAILED` | `ABORTED` and `report` holds the spec 003 report (providers, partition
failures, per-day contributing/missing providers, statistics) with each day's `outcome` and `storedItineraries`.
Errors: `400` (malformed id), `404` (unknown id / no runs), `409` (run in progress). Bodies: RFC 9457.

## Configuration (defaults)

| Property | Default | Why |
|---|---|---|
| `aggregator.ingestion.schedule.enabled` | `true` | Tests disable it |
| `aggregator.ingestion.schedule.initial-delay` | `10s` | Data appears soon after `docker compose up` (gds-mock is already healthy) |
| `aggregator.ingestion.schedule.fixed-delay` | `15m` | ADR-0006; above the 10 min run deadline, so runs never pile up |
| `aggregator.persistence.max-stale-age` | `2h` | 8 scheduled runs: a provider outage that long is no longer a blip, and fresh data from the others beats 2 h old prices (AC-5) |
| `aggregator.persistence.run-retention` | `7d` | Run history for the dashboard; ≈ 700 small rows |
| `spring.datasource.*` | `localhost:5432/flights` | Overridden by env vars in compose |

## Domain model impact

- `IngestionReport` / `DayReport` gain the per-day store outcome (`DayOutcome`: `REPLACED`, `PUBLISHED_INCOMPLETE`,
  `KEPT_STALE`, `NO_DATA`, `WRITE_FAILED`), the stored row count and the misfiled count (AC-9). `DayAggregationSink.publish` returns the outcome,
  and the sink is told about days that were not published (AC-5).
- The run id is chosen by the trigger (so `POST` can return it before the run ends) and passed to the use case.
- New in `ingestion.domain`: `IngestionRun` (id, trigger, status, started/finished, report), `IngestionTrigger`,
  `RunStatus` (adds `RUNNING`, `ABORTED` to the spec 003 statuses).

## Non-functional requirements

- **Bulk load:** COPY (PgJDBC `CopyManager`) and `JdbcClient` batching with `reWriteBatchedInserts` are both
  measured on 1M rows; the faster wins, target **well under 60 s for 1M rows** (a `slow`-tagged test measures it).
  PostgreSQL-specific code stays in the persistence adapter (ArchUnit rule).
- **Memory:** the store writes and drops each day, so the heap is the ingestion working set (≈ 512 MB with 2 days in
  flight, spec 003) plus encoding buffers. The new `-Xmx` is the smallest heap that completes a 1M run without
  `PARTIAL`, plus headroom, measured and justified in `SOLUTION.md`.
- **Tests:** one shared Testcontainers PostgreSQL (`@ServiceConnection`, same image as compose) for every IT; no
  sleeps beyond small polling with a timeout.
- **Observability:** INFO per run start/end (already) and per day outcome; WARN for `KEPT_STALE`, `WRITE_FAILED`,
  skipped ticks at INFO.

## Requirements for the search spec

- Row ids change whenever a day is replaced (new snapshot, new identity values). A client paginating with a keyset
  cursor across a replacement continues from the cursor's `(price, id)` position in the new snapshot, so it may skip
  or repeat results; the search spec must document this (ADR-0008 already notes cursors outliving a snapshot).
- Day status (`day_snapshot`: incomplete, stale since) is available to expose freshness in search results.
- Search must still filter departed flights at query time (SOLUTION.md limitation) and should exclude past days.

## Open questions

All resolved (2026-11-06):

- [x] Q1 Partial-day rule — whole-snapshot rule generalised (replace only if contributing ⊇ snapshot providers,
  else keep and mark stale); amends spec 003's per-provider wording. Alternatives in ADR-0017.
- [x] Q2 Max staleness — yes, `max-stale-age` = `2h` (AC-5).
- [x] Q3 Atomic replacement — `DELETE` + load + `day_snapshot` update in one transaction; partitioning recorded as
  the path for larger volumes; plus `ANALYZE itinerary` after each run's writes (AC-13).
- [x] Q4 Run lock — session-level `pg_try_advisory_lock` on a dedicated connection held for the whole run; checked
  before publishing each day, a lost lock stops and fails the run (AC-18, AC-19, ADR-0019).
- [x] Q5 In-memory store — moved to test sources as a fake.
- [x] Q6 Run history — 7 days (AC-12).
- [x] Q7 Misfiled itineraries — skipped, counted in the report per day and in total so figures reconcile (AC-9).
- [x] Q8 Dependencies — as listed; `postgres:17` in both docker-compose and Testcontainers. Spring Boot 4 splits
  auto-configuration into modules: `spring-boot-starter-jdbc` and `spring-boot-starter-flyway` (plain `flyway-core`
  is no longer auto-configured), `flyway-database-postgresql` and `postgresql` at runtime, `spring-boot-testcontainers`
  and `org.testcontainers:testcontainers-postgresql` (Testcontainers 2) for tests.
- [x] Q9 Scheduler — initial delay `10s` after ready, fixed delay from the end of the previous run.
