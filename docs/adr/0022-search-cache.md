# ADR-0022: Redis cache-aside keyed by a transactional data version, as a decorator of the query ports

- **Status:** Accepted
- **Date:** 2026-11-06
- **Related spec:** [specs/005-search-api](../../specs/005-search-api/spec.md) (AC-18 – AC-21)
- **Refines:** [ADR-0007](0007-postgresql-and-redis.md) (follow-up: cache key and TTL policy)

## Context

The statement names the system a *"Flight Aggregation & Cache Optimization System"*. Searches repeat (dashboard
defaults, the same popular routes), and the data only changes when an ingestion run publishes a day (every 15 min,
ADR-0006). The cache must never serve a page older than the data in PostgreSQL once a day has been republished or
marked stale, must not need a mass deletion on every run, must not break search when Redis fails, and must not leak
into the use case (ADR-0002). Departed itineraries must never be served (spec 005 AC-4), even from a page cached
before they departed.

## Decision

We will use **cache-aside in Redis**, implemented as **decorators of the outbound query ports**
(`ItinerarySearchQuery`, `CatalogQuery`) in `search/infrastructure/adapter/out/cache`; the use case and the
PostgreSQL adapter know nothing about Redis (ArchUnit: Spring Data Redis and Lettuce only in that package).

- **Data version.** A single-row table `data_version(version bigint)` is incremented by the ingestion persistence
  adapter **as the last statement of every transaction that changes `itinerary` or `day_snapshot`** (day replaced,
  day marked stale, retention). It is therefore visible exactly when the data is. A read port `DataVersionQuery`
  (owned by the search application, used only by the decorators) reads it: one primary-key read per request.
  Reading the version *before* the data under `READ COMMITTED` means a cached value is never older than its key's
  version (at worst newer, which is harmless).
- **Keys.** `search:v1:<version>:<normalized filters>:<limit>:<after position or "first">` and
  `catalog:v1:<version>`; human-readable for debugging. A new version simply stops hitting old keys — **no
  deletion**; old entries expire.
- **Values.** The port's result as JSON (items, plus the next position when there is one), written with a **TTL of
  10 min** — a safety net and a memory bound, not the invalidation mechanism. Redis runs with `maxmemory 256mb` and
  `allkeys-lru` in compose.
- **Time.** `now` is not part of the key (it would defeat caching). On a hit, the decorator **drops items that
  departed since they were cached** and keeps the stored next position, so the page can be shorter than `limit`, even
  empty, with `hasMore` true; the contract tells clients to keep paging (spec 005).
- **Failure.** Lettuce command and connect timeouts of 100 ms; any Redis error or timeout falls back to the delegate
  (PostgreSQL) for reads and skips the write. A Resilience4j **circuit breaker** (already a dependency, ADR-0014)
  opens after repeated failures and skips Redis for **30 s**, then probes it again; transitions are logged (WARN when
  it opens, INFO when it closes), individual errors at DEBUG, so an outage logs a few lines, not one per request.
  Micrometer counter `search.cache` tagged `result=hit|miss|error|bypassed`. Until the breaker opens (5 calls), a
  miss with Redis hung can wait for two timeouts (the `GET` and the `PUT`), so ≈ 200 ms on top of the database time
  for at most the first three requests.
- **Catalog single-flight** (added after the code review). The catalog is a grouped scan of every future row (≈ 420 ms
  at 1M rows). It is also memoised in the JVM per data version behind a lock, so a version bump, or a Redis outage,
  costs one scan per instance instead of one per concurrent request (which could exhaust the connection pool shared
  with ingestion). Days before today are dropped from a reused catalog.
- **Where `DataVersionQuery` lives.** It is an application port although only the decorators use it: the data version
  is a property of the read model the search application owns (it is not Redis-specific), and keeping it a port lets
  the decorators depend on an abstraction implemented by the persistence adapter instead of on that adapter.

## Alternatives considered

1. **Delete keys on every publication (`SCAN` + `DEL`, or a key set per day)** — explicit, but a run publishes up to 7
   days, a scan over all keys is O(keys), and a crash between the commit and the delete leaves stale pages until the
   TTL. Versioned keys make invalidation atomic with the commit. Rejected.
2. **Version from a sequence or Redis `INCR`** — a sequence advances before the commit, so a reader could cache old
   rows under the new version (stale until the next run); a Redis counter is lost with Redis, and the ingestion
   feature would have to talk to Redis. Rejected.
3. **Derive the version from `day_snapshot` per request** (e.g. a hash of its rows) — no schema change, but every
   reader re-implements "what counts as a change" and retention deletions are easy to miss. Rejected.
4. **Spring's `@Cacheable`** — annotations on a use case or port would put framework concerns in the application, and
   it has no hook for versioned keys, departed filtering or a breaker. Rejected.
5. **TTL only** — simple, but serves pages older than a refresh for up to the TTL (AC-19). Rejected.
6. **Cache at the use-case level (decorating `SearchItineraries`)** — would cache encoded cursors and responses, but
   the use case's validation and cursor checks are cheap and belong before the cache; caching the port result keeps
   one concern per layer. Rejected.

## Consequences

- **Positive:** stale pages are impossible after a refresh, with no cleanup job; the cache is invisible to the domain
  and the use case; Redis can disappear without search failing.
- **Negative / trade-offs:** every request, hit or miss, makes one PostgreSQL primary-key read (so PostgreSQL must be
  up even for hits); every run invalidates the whole cache, including days that did not change (a per-day version
  would keep more hits but complicate keys spanning several days); concurrent day transactions serialise briefly on
  the version row (held only from the last statement to the commit); a cached catalog can list a date or route whose
  last flight has just departed (until the TTL or the next run).
- **Follow-ups:** cached vs uncached latency in SOLUTION.md (AC-23); cache warming of popular searches after a run
  if the miss cost matters; register the breaker in a `CircuitBreakerRegistry` with Micrometer bindings if breaker
  state should appear in metrics (today: the `search.cache` counter and the WARN/INFO transition logs); signed
  cursors or caching only first pages if clients flood the cache with crafted positions (keys are client-controlled,
  bounded by `maxmemory` + LRU).
