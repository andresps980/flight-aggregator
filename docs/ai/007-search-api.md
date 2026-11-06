# 007 — Search API: filters, keyset pagination, indexes, Redis cache, catalog, OpenAPI (spec 005)

- **Date:** 2026-11-06
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `feat/005-search-api` (PR 7 of the repository)

## Prompts

### 1. Kick-off

````text
We continue the assessment. Read CLAUDE.md, docs/exercise.md, SOLUTION.md, docs/adr/, docs/ai/
and specs/ (especially follow-ups in specs 001 and 004: filter departed flights at query time;
ids change when a day is replaced) before planning. Same conventions as previous PRs:
English, SDD, SOLID applied pragmatically, docs/ai/ session record, SOLUTION.md updates,
merge-commit history. This is PR 7 in the repo.

Goal: the search API over the PostgreSQL read model — filtering, keyset pagination, indexes,
a Redis cache, and a measured performance test at 1M rows. The API contract defined here is
what the frontend (next PR) will consume, so it must be explicit and documented.

1. Run /spec-feature to write specs/005-search-api/spec.md. Cover at least:
   - GET /api/v1/itineraries with optional filters origin, destination (IATA), date,
     maxPrice, carrier, plus limit (default 20, max 100) and cursor.
     Date semantics (update the domain-assumptions ADR accordingly): without date, return
     all future departures (first departure after now); with date, that UTC day only.
     Departed itineraries are never returned (filtered at query time).
   - Ordering by final price ascending, then id. Keyset pagination with an opaque cursor
     (encodes the last (price, id) and a fingerprint of the filters, so a cursor reused with
     different filters is rejected with 400). Response: items, nextCursor, hasMore; no exact
     total count (justify; optionally an estimate if cheap). Each item: key fields, segments,
     base/final price, selected provider, providers, stops, and the day's freshness
     (complete / incomplete / stale) from day_snapshot.
   - Document the known limitation that a day replacement during pagination may skip or
     repeat a result (ids change), and why it's acceptable.
   - Validation errors → 400 ProblemDetail (bad IATA, negative maxPrice, limit out of
     range, malformed or mismatched cursor, date outside a sane range).
   - A small catalog endpoint for the dashboard's filter dropdowns (e.g. GET /api/v1/catalog
     with available origins, destinations, carriers and dates), cached as well.
   - Indexes: choose the minimal set that serves the common filter combinations with an index
     range scan in keyset order, justified with EXPLAIN (ANALYZE, BUFFERS) on 1M rows, and
     measure their cost on the bulk load (COPY time before/after).
   - Redis cache, cache-aside: key = normalized filters + cursor + data version; the data
     version changes whenever an ingestion run publishes new data, so stale entries are never
     served after a refresh (no mass deletion needed); TTL as a safety net. If Redis is down
     or slow, search still works from PostgreSQL (short timeout, fallback, logged/metric).
   - Performance: on 1M rows, first page and deep pages (e.g. page 1,000) with keyset vs the
     equivalent OFFSET query, cached vs uncached latency; numbers recorded in SOLUTION.md to
     justify keyset vs offset.
   - API documentation: an OpenAPI description of the search and catalog endpoints (check
     springdoc compatibility with Spring Boot 4; if not available, a hand-written
     docs/api/openapi.yaml validated in a test) plus examples in docs/usage.md.
   Show me the spec with open questions and wait for my approval.

2. Design guidance (justify; /adr for index strategy, cursor format, cache keying and
   invalidation): a search use case in the application layer with a query port implemented
   by the PostgreSQL adapter; caching as a decorator around the query port (or a cache port),
   so the use case doesn't know about Redis; Redis code only in its adapter. ArchUnit rules
   for Redis like the ones for PostgreSQL.

3. After I approve the spec, /implement-spec 005 on feat/005-search-api. Tests first:
   - Unit: cursor encode/decode and tampering, filter validation, cache key normalization,
     cache decorator (hit, miss, version change, Redis failure → fallback).
   - ITs with Testcontainers (shared PostgreSQL as before, plus a shared Redis container):
     each filter and combinations; ordering and ties on equal price; walking all pages yields
     every matching row exactly once; departed excluded; date default; maxPrice boundary;
     cursor with different filters → 400; freshness per item; cache hit served after first
     call and invalidated after a new run; Redis stopped → search still answers.
   - Slow-tagged performance test on 1M rows with the measurements above.
   After writing the tests (before implementing), stop and show me the list grouped by AC.

4. docker-compose: add redis (healthcheck) and wire the aggregator to it.

5. Run code-reviewer and spec-reviewer, fix findings, record in docs/ai/. Update
   docs/architecture.md, docs/usage.md, README, SOLUTION.md (pagination justification with
   measured numbers, cache strategy, limitations).

6. ./mvnw verify green (report duration); docker compose up --build healthy and a search
   returning data after the first ingestion. Then /pr-description, push after my OK, and check
   CI and code scanning as before.
````

### 2. Spec approval with changes

````text
Spec approved with these answers:

Q1 yes. Q2 yes — and state in the API contract that a page served from cache may be shorter
than limit (even empty) while hasMore is true, so clients must keep paging. Q3 unsigned with
the filter fingerprint; explain in the ADR why (public data, tampering only changes the
starting position). Q4 leave out. Q5 yes. Q6 yes, include routes (origin → destinations) in
the catalog now so the frontend PR doesn't need backend changes. Q7 yes, reuse Resilience4j.
Q8 yes. Q9 yes. Q10 yes, accept an in-memory sort when the matching set is small and
measured; justify it in the index ADR with EXPLAIN output, and include the worst realistic
case (busiest route, no date, high maxPrice) in the performance test.

Mark the spec Approved, write the ADRs, amend ADR-0010, then write the tests and stop with
the list grouped by AC.
````

### 3. Test review checkpoint

````text
Test list approved. Before implementing:
1. Re-read the new test files after the formatter rewrite and fix anything odd.
2. Extend MigrationsIT to cover the data_version table and the new search indexes.
3. Commit the docs: and test: commits (if not already committed).
Then implement, add redis to docker-compose, run the 1M slow test and record its numbers in
ADR-0020 (then mark it Accepted) and SOLUTION.md, run both reviewers, update docs and
docs/ai/, run ./mvnw verify (report duration) and prepare the PR with /pr-description.
````

The session hit the account's usage limit once, after the tests were written and before they were committed; the
Stop hook then reported the (intentionally) failing build, and work resumed with prompt 3.

## Clarifications (open questions of the spec)

| # | Question | Answer |
|---|---|---|
| Q1 | `date` outside [today − 1, today + 365] → 400? | Yes |
| Q2 | Drop departed items from cached pages (page may be short)? | Yes, and state it in the contract |
| Q3 | Unsigned cursor or HMAC? | Unsigned with the filter fingerprint; rationale in ADR-0021 |
| Q4 | Count estimate? | Left out |
| Q5 | Data version as a single-row table bumped in the writing transaction? | Yes |
| Q6 | Routes in the catalog? | Yes, now |
| Q7 | Breaker for Redis? | Yes, Resilience4j |
| Q8 | springdoc + Redis dependencies | Yes (springdoc 3.2.0 is built against Spring Boot 4.1.2, checked on Maven Central) |
| Q9 | origin = destination → empty 200 | Yes |
| Q10 | Accept an in-memory sort for small sets? | Yes, if measured; add the worst realistic case to the test |

## Skills, agents and hooks involved

- Skills: `spec-feature`, `adr` (ADR-0020, 0021, 0022), `implement-spec`, `pr-description`.
- Agents: `code-reviewer`, `spec-reviewer`.
- Hooks: `format-java.sh` (Spotless after every edit), `verify-on-stop.sh` (it flagged the red test-first state at the
  usage-limit stop, as expected), `guard-git.sh`, `SessionStart` (reminded to branch off `main`).

## What was done

- Spec 005 with 25 acceptance criteria; ADR-0020 (indexes, accepted after measuring), ADR-0021 (cursor), ADR-0022
  (cache keying and invalidation); ADR-0010 item 6 amended (date default, query-time departed filter).
- Tests first (red commit), grouped by AC: domain and use-case unit tests, cache decorator and breaker tests on a fake
  store, HTTP ITs on a shared PostgreSQL and a new shared Redis container, a data-version IT on the ingestion store,
  an OpenAPI contract IT, MigrationsIT extended, a `slow` 1M-row performance IT, two ArchUnit rules.
- Implementation inside-out: `search` domain, two use cases and three ports, PostgreSQL adapter (`ItinerarySearchSql`,
  page and catalog queries, data version), Redis decorators and `ResilientCacheStore`, controllers and DTOs, wiring,
  Flyway V2, the version bump in `PostgresDaySnapshotStore`, springdoc scoped to the search paths.
- `redis:8` in docker-compose (healthcheck, no persistence, 256 MB LRU); the aggregator waits for it.
- Measurements at 1M rows in ADR-0020, SOLUTION.md and `docs/measurements/search-1m.md`.

## Notable corrections during the session

- **`char(n)` vs `varchar` parameters.** A string placeholder compared with a `char(3)` column makes PostgreSQL cast
  the column, which disables the index; the query casts the placeholder to `bpchar` instead (decided before the
  first run, confirmed by the plans: `Index Cond: (origin = 'FRA'::bpchar) …`).
- **Domain enum in a DTO.** The first `FreshnessResponse` exposed the domain `FreshnessStatus`; CLAUDE.md requires
  DTO-only controllers, so it became a string with `allowableValues` in the OpenAPI schema.
- **URI encoding in a test.** `RestClient` encodes URI templates, so `%20` in a test URL was sent as `%2520`.
- **Redis pause semantics.** `SET`s that timed out while Redis was paused were already on the socket and ran when it
  resumed; the recovery test's expectation (old cached price) was wrong, now it asserts the PostgreSQL price.
- **Idempotent unpause.** `@AfterEach` unpaused Redis unconditionally and Docker refused for a running container.
- **OpenAPI scope.** springdoc first described every controller, including the ingestion endpoints with a wrong
  `200 */*` response; scoped to the search paths (`springdoc.paths-to-match`), JSON content type declared, a title
  added.
- **Locale-dependent report.** The performance report printed `13,0 s` and `1.000.006` on this machine; formatted with
  `Locale.ROOT` now.
- **A measured miss.** The performance test found one combination slower than the 50 ms target (origin + carrier + low
  `maxPrice`, 51 ms median in the first run, 49 ms in the recorded re-run): two filters outside the route index. Recorded in ADR-0020 instead of adding a third
  index, since the author asked for the minimal set and it is not one of AC-22's combinations.
- **No in-memory sort needed.** The Q10 allowance was never used: the route index already returns a route in price
  order.

## Review findings and how they were handled

### `code-reviewer` (verdict: needs changes; no blocking defects, one "should fix")

It ran `./mvnw verify` (green) and confirmed the SQL placeholders and `bpchar` casts, keyset boundaries, cursor
validation, departed filtering on cached pages, the transaction semantics of the version bump (last statement, rolled
back with the day, serialised under READ COMMITTED) and the breaker accounting.

| # | Finding | Action |
|---|---|---|
| 1 | Catalog stampede: every version bump (up to 7 per run), or every request with Redis down, runs the 450 ms scan; concurrent loads can exhaust the 10-connection pool shared with ingestion | **Fixed**: per-version single-flight memo in `CachingCatalogQuery` (unit test: 8 concurrent misses with Redis down → one query) |
| 2 | A cached catalog lists yesterday after midnight | **Fixed**: past days dropped on reuse (unit test) |
| 3 | Cache keys are client-controlled (crafted cursors / prices) | Documented (SOLUTION.md, ADR-0022 follow-up); bounded by `maxmemory` + LRU |
| 4 | `/actuator/metrics` exposed without auth | Kept (the spec asks for it); listed as a limitation |
| 5 | Cache format = the domain records' JSON; a rename could decode silently | **Fixed**: golden-format test that names the key prefix to bump |
| 6 | `DataVersionQuery` is an application port used only by adapters | Kept; rationale added to ADR-0022; architecture.md states the features meet only in the schema |
| 7 | Cache breaker not in a `CircuitBreakerRegistry` | ADR-0022 follow-up (counter and transition logs cover it today) |
| 8 | An `Error` in a half-open probe could leak the permission | **Fixed**: recorded, then rethrown (unit test) |
| 9 | Missing null checks in the decorators; `limit ≤ 0` would throw `IndexOutOfBounds` | **Fixed** (guards + port Javadoc) |
| 10 | Duplicated decorator flow | Not changed: the catalog decorator now differs (memo, date filter) |
| 11 | Breaker state could leak between tests | Already handled: `SearchSpringTest` resets the breaker before each test |

### `spec-reviewer` (verdict: gaps found, all minor; AC-1 – AC-21 and AC-24 implemented and tested)

| Gap | Action |
|---|---|
| Session log not committed yet | Committed with the review fixes |
| AC-22: plans missing for origin / carrier / route / route + date **with** `maxPrice` | **Fixed**: four combinations added to the performance test, re-run, plans recorded |
| 51 ms for origin + carrier + low `maxPrice` | Already in ADR-0020; mentioned in the PR |
| Cached catalog can list a route whose last flight departed | Documented in usage.md; past days now dropped (code review #2) |
| Spec says Redis only in the cache adapter; ArchUnit also allows the composition root | Spec text updated |
| Up to two Redis timeouts per request until the breaker opens | Documented in ADR-0022 and SOLUTION.md |
| OpenAPI patterns are stricter than the trimming server | Kept: clients should send clean codes; the server is lenient |

## Verification

- `./mvnw verify` (both modules, unit + integration tests, ArchUnit, coverage gates, Spotless): **green in 66 s**
  (flight-aggregator 506 unit + 177 integration tests, 50 s; gds-mock 146 + 41, 14 s).
- `SearchPerformanceIT` (`-Pslow-tests`): green, ≈ 2 min; its report is `docs/measurements/search-1m.md`.
- `docker compose up --build` (AC-25, by hand, twice — before and after the review fixes): `postgres`, `redis`,
  `gds-mock` and `flight-aggregator` healthy, the aggregator started after the other three; the first scheduled run
  `COMPLETED` (94,543 rows, 17–21 s); a search returned cheapest-first data with `COMPLETE` freshness, the repeated
  request was a cache hit (`search.cache` hit counter up, no errors), page 2 followed the cursor, the same cursor with
  other filters answered `400`, and the catalog listed 34 origins and 7 dates.
