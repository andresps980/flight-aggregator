# Runtime flows

The two paths that matter at runtime, as sequence diagrams derived from the code on `main`. Participants are the
classes named in the [package pages](packages/README.md); the static structure is in [overview.md](overview.md).

## Ingestion run

Started by the scheduler (`SCHEDULED`, 10 s after startup, then 15 min after each run ends) or by
`POST /api/v1/ingestions` (`MANUAL`). Decisions: [ADR-0006](../adr/0006-scheduled-pre-ingestion.md) (schedule),
[ADR-0019](../adr/0019-ingestion-run-lock.md) (lock), [ADR-0014](../adr/0014-provider-resilience.md) (retries and
breakers), [ADR-0015](../adr/0015-atomic-partition-buffers.md) (atomic partitions),
[ADR-0016](../adr/0016-day-partitioned-aggregation.md) (one day at a time),
[ADR-0017](../adr/0017-day-snapshot-replacement.md) / [ADR-0018](../adr/0018-bulk-load.md) (day snapshots, `COPY`),
[ADR-0022](../adr/0022-search-cache.md) (data version).

### 1. Start: lock, orphan clean-up, `202`

```mermaid
sequenceDiagram
    autonumber
    actor Client as Dashboard / curl
    participant Ctl as IngestionController
    participant Sched as IngestionScheduler
    participant Run as IngestionRunService
    participant Lock as PostgresAdvisoryRunLock
    participant Repo as PostgresIngestionRunRepository
    participant Exec as IngestionRunExecutor
    participant PG as PostgreSQL

    alt manual
        Client->>Ctl: POST /api/v1/ingestions (through nginx /api)
        Ctl->>Run: start(MANUAL)
    else scheduled tick
        Sched->>Run: start(SCHEDULED)
    end
    Run->>Lock: tryAcquire()
    Lock->>PG: SELECT pg_try_advisory_lock(…) on a dedicated, non-pooled connection
    alt another run holds the lock (any instance)
        Lock-->>Run: empty
        Run->>Repo: findRunning()
        Run-->>Ctl: IngestionAlreadyRunningException
        Ctl-->>Client: 409 Problem Details + runningIngestionId
        Note over Sched: a scheduled tick is simply skipped
    else lock acquired
        Lock-->>Run: RunLease (session kept open for the whole run)
        Run->>Repo: abortRunning(now) — RUNNING rows of a dead process become ABORTED
        Run->>Repo: create(run RUNNING)
        Run->>Exec: execute(run) on a virtual thread
        Run-->>Ctl: StartedIngestion(run)
        Ctl-->>Client: 202 Accepted + Location: /api/v1/ingestions/{id}
    end
```

### 2. Execute: parallel fetch, per-day aggregation, snapshot publish

```mermaid
sequenceDiagram
    autonumber
    participant Run as IngestionRunService
    participant Ing as IngestionService
    participant Day as day coordinator (virtual thread)
    participant Feed as HttpProviderFeed ×3
    participant Mock as gds-mock
    participant Agg as OfferAggregator (one per day)
    participant Pub as DaySnapshotPublisher
    participant Store as PostgresDaySnapshotStore
    participant PG as PostgreSQL

    Run->>Store: deleteDaysBefore(today) (retention, runs older than 7 days go through the run repository)
    Run->>Ing: ingest(runId, guard = lease::isHeld)
    Note over Ing: reads "now" once, builds the horizon (7 UTC days after today)<br/>and one ItineraryValidator, run deadline 10 min
    loop each horizon day, at most max-concurrent-days (2) at a time
        Ing->>Day: runDay(day)
        par each provider, at most max-concurrent-partitions (2) per provider
            Day->>Feed: fetch(day)
            loop Retry: up to 3 attempts, backoff 500 ms → 1 s ± 50 %, provider failures only
                Feed->>Feed: CircuitBreaker permits? (OPEN → CIRCUIT_OPEN, no request)
                Feed->>Mock: GET /{provider}/flights?date=day (attempt on its own virtual thread,<br/>read timeout 6 s, attempt deadline 60 s)
                Mock-->>Feed: body streamed: JSON · XML · CSV (or 500 / 503 / slow)
                Feed->>Feed: FeedParser → fresh PartitionBuffer (malformed records counted, skipped)
            end
            alt attempt completed
                Feed-->>Day: ProviderPartition (complete buffer only)
                Day->>Agg: accept(offer) for each offer, then the partition is dropped
            else every attempt failed / breaker open / run deadline
                Feed-->>Day: PartitionFetchException(kind, attempts) → provider missing for this day
            end
        end
        Day->>Day: guard.mayPublish()? (lease still held, if not: stop publishing → run FAILED)
        alt at least one provider delivered the day
            Day->>Pub: publish(DayAggregation: result, contributing, missing)
            Pub->>Pub: drop itineraries departing on another UTC day (misfiled)
            Pub->>Store: find(day) → stored DaySnapshot
            Pub->>Pub: SnapshotPolicy.decide → REPLACE or KEEP_STALE
            alt REPLACE
                Pub->>Store: replace(DaySnapshotWrite)
                Store->>PG: one transaction: DELETE day's rows · COPY new rows ·<br/>upsert day_snapshot · UPDATE data_version + 1
            else KEEP_STALE (a provider is missing and the stored day is more complete)
                Pub->>Store: markStale(day) — also bumps data_version
            end
            Pub-->>Day: DayPublication (REPLACED, PUBLISHED_INCOMPLETE, KEPT_STALE or WRITE_FAILED)
        else no provider delivered
            Day->>Pub: unavailable(day)
            alt nothing stored for the day
                Pub-->>Day: NO_DATA
            else a snapshot is stored
                Pub->>Store: markStale(day) — bumps data_version
                Pub-->>Day: KEPT_STALE
            end
        end
        Day-->>Ing: DayReport (statistics summed into the run)
    end
    Ing-->>Run: IngestionReport (per provider: status, failures, attempts, breaker state, malformed)
    Run->>Store: refreshStatistics() — ANALYZE itinerary
    Run->>PG: update run: COMPLETED · PARTIAL · FAILED (every provider failed, lock lost or unexpected error), report as JSONB
    Run->>Run: lease.close() — advisory lock released with the session
```

**What a reader sees meanwhile.** Each day switches atomically from its old snapshot to the new one at its commit;
the `data_version` bump in the same transaction makes the next search miss the cache and read the new rows. The
dashboard polls `GET /api/v1/ingestions/latest` every 30 s (3 s while a run is `RUNNING`) and offers "New data
available" when a run published data ([ADR-0023](../adr/0023-real-time-by-polling.md)).

**One provider down** (the statement's resiliency case): its partitions fail fast once its breaker is `OPEN`, the
other two providers still deliver every day, each day is published with that provider *missing*, and
`SnapshotPolicy` keeps the previous, more complete snapshot (marked stale, at most 2 h) instead of dropping the
provider's offers. The run ends `PARTIAL`; search keeps answering. `scripts/stack-smoke.sh` checks exactly this in CI.

## Search request

`GET /api/v1/itineraries?origin=…&destination=…&date=…&maxPrice=…&carrier=…&limit=…&cursor=…` — search never calls the
providers. Decisions: [ADR-0008](../adr/0008-keyset-pagination.md) (keyset), [ADR-0020](../adr/0020-search-indexes.md)
(indexes), [ADR-0021](../adr/0021-search-cursor.md) (cursor), [ADR-0022](../adr/0022-search-cache.md) (cache).

```mermaid
sequenceDiagram
    autonumber
    actor Browser
    participant Nginx as nginx (frontend)
    participant Ctl as ItinerarySearchController
    participant Svc as ItinerarySearchService
    participant Cache as CachingItinerarySearchQuery
    participant Ver as PostgresDataVersionQuery
    participant RCS as ResilientCacheStore
    participant Redis
    participant SQL as PostgresItinerarySearchQuery
    participant PG as PostgreSQL

    Browser->>Nginx: GET /api/v1/itineraries?…
    Nginx->>Ctl: proxy_pass (same origin, 15 s read timeout)
    Ctl->>Svc: search(SearchRequest of raw strings)
    Svc->>Svc: SearchCriteria.parse, PageSize.parse (400 naming the parameter)
    opt cursor present
        Svc->>Svc: Cursor.decode + requireIssuedFor(criteria.fingerprint()) (400 if other filters)
    end
    Svc->>Cache: find(criteria, after, limit, now)
    Cache->>Ver: current()
    Ver->>PG: SELECT version FROM data_version (one primary-key read, every request)
    Cache->>RCS: get(key = v1 · version · filters · limit · position)
    alt breaker CLOSED and Redis answers within 100 ms
        RCS->>Redis: GET key
        Redis-->>RCS: page or nil
    else Redis down, slow or breaker OPEN
        RCS-->>Cache: empty (error or bypassed counted in search.cache)
    end
    alt hit
        Cache->>Cache: drop itineraries departed since cached (page may be short, hasMore kept)
    else miss (or Redis unavailable)
        Cache->>SQL: find(criteria, after, limit, now)
        Note over SQL: asks for limit + 1 rows to know whether more follow
        SQL->>PG: SELECT … FROM itinerary JOIN day_snapshot<br/>WHERE departure_at > now AND filters AND (final_price, id) > (cursor)<br/>ORDER BY final_price, id LIMIT limit + 1
        Note over PG: index range scan on (final_price, id) or<br/>(origin, destination, final_price, id), stopped by LIMIT
        PG-->>SQL: rows
        SQL-->>Cache: SearchSlice(items, next position)
        Cache->>RCS: put(key, page, TTL 10 min) (skipped or failed silently if Redis is unavailable)
    end
    Cache-->>Svc: SearchSlice
    Svc->>Svc: next cursor = encode(last position, fingerprint) if more rows follow
    Svc-->>Ctl: SearchPage
    Ctl-->>Nginx: 200 ItineraryPageResponse (items, nextCursor, hasMore)
    Nginx-->>Browser: 200
```

**Redis down.** Every failed call counts against a Resilience4j breaker (window 10 calls, opens at 50 % failures
after at least 5 calls). Until it opens, a request can wait for two 100 ms timeouts (get and put); once open, Redis is
skipped for 30 s (`search.cache{result=bypassed}`), then probed again. Searches keep answering from PostgreSQL
throughout, and readiness does not depend on Redis. PostgreSQL itself must be up even for cache hits, because the
data version is read on every request.

**The catalog** (`GET /api/v1/catalog`) follows the same path through `CachingCatalogQuery`, with one addition: the
result is also memoised in the JVM per data version behind a lock, so a version bump or a Redis outage costs one
grouped scan (≈ 420 ms at 1M rows) instead of one per concurrent request.
