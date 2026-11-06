# Spec 005: Search API over the read model, keyset pagination, Redis cache

- **Status:** Implemented
- **Branch:** feat/005-search-api
- **Related ADRs:** [ADR-0007](../../docs/adr/0007-postgresql-and-redis.md),
  [ADR-0008](../../docs/adr/0008-keyset-pagination.md), [ADR-0010](../../docs/adr/0010-domain-assumptions.md),
  [ADR-0017](../../docs/adr/0017-day-snapshot-replacement.md), [ADR-0018](../../docs/adr/0018-bulk-load.md);
  [ADR-0020](../../docs/adr/0020-search-indexes.md), [ADR-0021](../../docs/adr/0021-search-cursor.md),
  [ADR-0022](../../docs/adr/0022-search-cache.md)

## Context

From the [statement](../../docs/exercise.md): *"Implement a clean search API supporting high-performance filtering (by
Origin, Destination, Max Price, and Carrier) using an optimized pagination strategy (justify your choice of Keyset vs.
Offset in your documentation)"*, and SOLUTION.md must justify *"pagination handling up to 1,000,000 rows"*. The
system is a *"Flight Aggregation & Cache Optimization System"*.

Spec 004 built the PostgreSQL read model (`itinerary`, one denormalized row per itinerary; `day_snapshot`, the
freshness of each day) and left three requirements for this spec: filter departed flights at query time, document
that row ids change when a day is replaced, and expose day freshness. This spec adds the search and catalog
endpoints the dashboard (next PR) will consume, their indexes, a Redis cache, the OpenAPI description, and measures
keyset vs offset at 1M rows.

## Definitions

- **now** — the instant of the injected `Clock` when the request is handled.
- **Future itinerary** — its first departure (`departure_at`) is strictly after `now`. Only future itineraries are
  ever returned (spec 001 Q5 uses the same "not strictly after = departed" rule).
- **Data version** — an opaque value that changes in the same transaction as every change to `itinerary` or
  `day_snapshot` (day replaced, day marked stale, retention).
- **Freshness of a day** — from `day_snapshot`: `STALE` if `stale_since` is set, else `INCOMPLETE` if not complete,
  else `COMPLETE`.

## Scope

**In scope**
- `GET /api/v1/itineraries` (filters, ordering, keyset pagination, freshness per item) and `GET /api/v1/catalog`.
- Flyway migration: search indexes and the data version; the ingestion persistence adapter bumps the version.
- Redis cache-aside for both endpoints, keyed by data version; fallback to PostgreSQL when Redis fails.
- OpenAPI description of both endpoints, checked by a test; examples in `docs/usage.md`.
- Measurements at 1M rows: `EXPLAIN (ANALYZE, BUFFERS)` per index candidate, `COPY` time with and without the new
  indexes, keyset vs `OFFSET` (first and deep pages), cached vs uncached latency.
- `redis` in docker-compose (healthcheck), wired to the aggregator.
- ADR-0010 item 6 (date default) amended; new ADRs for indexes, cursor, cache.

**Out of scope**
- The frontend (next PR), authentication, rate limiting.
- Other sort orders (by departure time, duration): each needs its own index and cursor format (ADR-0008).
- Exact total counts, "jump to page N", backwards pagination (`prevCursor`).
- Filters beyond the five listed (stops, time-of-day, provider), multi-value filters, round trips.
- Cache warming, Redis persistence or clustering.

## Acceptance criteria

**Filters** — every filter is optional; filters combine with AND.
- **AC-1** — Given itineraries on several routes, carriers and prices, when searching with `origin`, `destination`,
  `carrier` and/or `maxPrice`, then exactly the future itineraries matching every given filter are returned:
  `origin` = first origin, `destination` = last destination, `carrier` = airline, `final_price ≤ maxPrice`
  (inclusive: an itinerary priced exactly `maxPrice` is returned, one cent above is not).
- **AC-2** — Codes are case-insensitive and trimmed (`mad` = `MAD`); `maxPrice=100` and `maxPrice=100.00` are the
  same filter (same results, same cursor fingerprint, same cache key).
- **AC-3 (date default)** — Given no `date`, then every future itinerary (any day) is eligible. Given `date`, then
  only future itineraries whose first departure falls on that UTC day are eligible.
- **AC-4 (departed)** — Given an itinerary stored with `departure_at ≤ now` (data written before it departed), then
  it is never returned, with or without `date`, on any page, cached or not.

**Ordering and pagination** ([ADR-0008](../../docs/adr/0008-keyset-pagination.md))
- **AC-5** — Results are ordered by `finalPrice` ascending, then by an internal unique id ascending, so itineraries
  with equal prices have a stable, total order.
- **AC-6** — Given `limit` absent, then at most 20 items are returned; given `limit` in 1–100, at most `limit`.
- **AC-7** — Given more matching items than `limit`, then `hasMore` is `true` and `nextCursor` is set; requesting the
  same filters with that cursor returns the items that follow. On the last page `hasMore` is `false` and
  `nextCursor` is `null`. `limit` may change between pages.
- **AC-8** — Given data that does not change while paging, when walking every page of a search, then every matching
  itinerary is returned exactly once, in the AC-5 order (including ties across a page boundary).
- **AC-9** — The response has no exact total count (see *Pagination notes*).

**Response content**
- **AC-10** — Each item carries: `itineraryKey`, `origin`, `destination`, `departureDate`, `departureAt`,
  `arrivalAt`, `carrier`, `stops`, `segments` (in order: flight number, origin, destination, departure, arrival),
  `basePrice`, `finalPrice`, `currency` (`EUR`), `selectedProvider`, `providers`, and `freshness` (`status`
  `COMPLETE` | `INCOMPLETE` | `STALE`, `publishedAt`, `missingProviders`, `staleSince`) of its day.
- **AC-11** — Given no matching itinerary, then `200` with `items: []`, `hasMore: false`, `nextCursor: null`.

**Validation** — `400` ProblemDetail whose `detail` names the offending parameter; nothing is queried.
- **AC-12 (error)** — `origin` / `destination` not 3 letters; `carrier` not 2 characters A–Z/0–9.
- **AC-13 (error)** — `maxPrice` not a decimal, negative, more than 2 decimals or ≥ 1,000,000,000 (beyond any
  storable price).
- **AC-14 (error)** — `limit` not an integer or outside 1–100.
- **AC-15 (error)** — `date` not `YYYY-MM-DD`, or outside [today − 1 day, today + 365 days] (UTC).
- **AC-16 (error)** — `cursor` malformed (not base64url, unknown version, unparsable fields), or created for
  different filters than the request's (fingerprint mismatch).

**Catalog**
- **AC-17** — `GET /api/v1/catalog` returns, over the future itineraries, the sorted distinct `origins`,
  `destinations`, `carriers`, departure `dates` (each date with its freshness) and `routes` (each origin with its
  sorted destinations), so the dashboard can fill dependent dropdowns; with no data, empty lists.

**Cache** ([ADR-0022](../../docs/adr/0022-search-cache.md))
- **AC-18** — Given a search (or the catalog) served once, when the same request is repeated with the data version
  unchanged, then it is served from Redis without querying the itinerary table, with the same body.
- **AC-19** — Given a cached search, when an ingestion run replaces a day (or marks one stale), then the next
  identical request is answered from PostgreSQL with the new data (the data version changed; no key deletion). Old
  entries expire by TTL.
- **AC-20** — Given a cached page containing an itinerary that has departed since it was cached, then that item is
  not served (AC-4 holds for cached pages too); the page keeps its `nextCursor` / `hasMore`, so it may be shorter
  than `limit`, even empty, while `hasMore` is `true`.
- **AC-21 (degraded)** — Given Redis down, unreachable or slower than the command timeout, then search and catalog
  still answer `200` from PostgreSQL with the same body, within the timeout plus the database time; the failure is
  logged at WARN (not per request while it lasts) and counted in a metric; requests go back to Redis once it
  recovers.

**Indexes and performance** ([ADR-0020](../../docs/adr/0020-search-indexes.md))
- **AC-22** — With 1M rows, the common filter combinations (none; origin + destination; origin + destination + date;
  origin; carrier; date; each with or without `maxPrice`) are answered without a sequential scan: either an index
  scan in keyset order stopped by `LIMIT`, or — only where the matching set is small and measured (e.g. one route) —
  an index scan of that set plus an in-memory sort. `EXPLAIN (ANALYZE, BUFFERS)` for each is recorded in the ADR,
  with the chosen (minimal) index set and the `COPY` time of 1M rows before and after it.
- **AC-23** — A `slow`-tagged test on 1M rows measures, per filter combination: first page and page 1,000 with keyset
  and with the equivalent `OFFSET` query, and cached vs uncached latency through the API (median and p95),
  including the worst realistic case (the busiest route, no date, a `maxPrice` above every price). The numbers are
  recorded in SOLUTION.md.

**Contract and stack**
- **AC-24** — The OpenAPI description of both endpoints (parameters, constraints, response and ProblemDetail
  schemas, examples) is published by the application and kept in `docs/api/`; a test fails if they diverge.
- **AC-25** — Given `docker compose up --build`, then `redis` is healthy, the aggregator starts after it, and after
  the first ingestion a search returns data and a repeated search is a cache hit. Checked by hand for this PR.

## API contract

`GET /api/v1/itineraries?origin=MAD&destination=JFK&date=2026-11-08&maxPrice=650&carrier=IB&limit=2&cursor=…`

Response `200`:
```json
{
  "items": [
    {
      "itineraryKey": "IB6251:MAD-JFK@2026-11-08T10:15:00Z",
      "origin": "MAD", "destination": "JFK", "departureDate": "2026-11-08",
      "departureAt": "2026-11-08T10:15:00Z", "arrivalAt": "2026-11-08T18:40:00Z",
      "carrier": "IB", "stops": 0,
      "segments": [ { "flightNumber": "IB6251", "origin": "MAD", "destination": "JFK",
                      "departureAt": "2026-11-08T10:15:00Z", "arrivalAt": "2026-11-08T18:40:00Z" } ],
      "basePrice": 412.38, "finalPrice": 433.00, "currency": "EUR",
      "selectedProvider": "mock-alpha", "providers": ["mock-alpha", "mock-gamma"],
      "freshness": { "status": "COMPLETE", "publishedAt": "2026-11-06T10:00:21Z",
                     "missingProviders": [], "staleSince": null }
    }
  ],
  "nextCursor": "djF8NDMzLjAwfDgyMTM0fDdmM2E5YzEyZTRiMDVkNjg",
  "hasMore": true
}
```
Prices are JSON numbers with 2 decimals; instants ISO-8601 UTC. The internal id is not exposed (it changes on every
snapshot); `itineraryKey` is the stable identity.

**Paging rule for clients:** keep requesting while `hasMore` is `true`. A page served from the cache omits
itineraries that departed after it was cached, so it **may be shorter than `limit`, or even empty, while `hasMore`
is `true`**; an empty `items` alone never means the end.

`GET /api/v1/catalog` → `200`:
```json
{ "origins": ["AMS", "BCN", "MAD"], "destinations": ["JFK", "LHR"], "carriers": ["IB", "VY"],
  "dates": [ { "date": "2026-11-06", "freshness": { "status": "COMPLETE", "publishedAt": "…",
               "missingProviders": [], "staleSince": null } } ],
  "routes": [ { "origin": "AMS", "destinations": ["JFK"] }, { "origin": "MAD", "destinations": ["JFK", "LHR"] } ] }
```

Errors: `400` (AC-12 – AC-16), body RFC 9457:
```json
{ "type": "about:blank", "title": "Invalid request", "status": 400,
  "detail": "origin must be a 3-letter IATA airport code", "instance": "/api/v1/itineraries" }
```

**Cursor** ([ADR-0021](../../docs/adr/0021-search-cursor.md)): base64url (no padding) of `v1|<finalPrice>|<id>|<fingerprint>`, where the fingerprint is the
first 64 bits of SHA-256 over the normalized filters (`origin`, `destination`, `date`, `maxPrice`, `carrier`; not
`limit`). Clients must treat it as opaque. Editing the position inside a cursor is not prevented (it only moves the
seek point within data the client may query anyway).

## Pagination notes

- **Keyset vs offset**: a page is `WHERE <filters> AND (final_price, id) > (:p, :id) ORDER BY final_price, id LIMIT
  :limit + 1` — an index seek whose cost does not depend on the depth; `OFFSET n` reads and discards `n` rows.
  AC-23 measures both.
- **No total count**: an exact `COUNT(*)` reads every matching row (≈ 1M for an unfiltered search) on each request,
  defeating the index-limited page; the dashboard needs "more results", not "page 37 of 4,512". An estimate is
  left out (Q4).
- **Known limitation — replacement during pagination**: a day's replacement gives its rows new ids (spec 004). A
  cursor taken before continues from its `(price, id)` position in the new data, so a client may miss an itinerary
  or see one twice at the boundary, and prices may differ between pages. It is acceptable because (a) every page is
  internally consistent, (b) it only affects a walk that spans a refresh (every 15 min, a few seconds of writes),
  (c) the cheapest-first listing is a browsing view, not a reconciliation; rejecting old cursors would force users
  back to page 1 after every run. Documented in SOLUTION.md and `docs/usage.md`.

## Domain model impact

New feature `com.andresps.flights.search` (no Java dependency on `ingestion`; it reuses `aggregation` value objects
`AirportCode` / `AirlineCode` for validation):
- `SearchCriteria` — normalized filters with their invariants (AC-2, AC-12 – AC-15), and their fingerprint.
- `PageRequest` / `Cursor` — limit 1–100, position `(finalPrice, id)` + fingerprint; codec in plain Java (AC-16).
- `ItineraryView`, `Freshness`, `SearchPage` (items, nextCursor, hasMore), `Catalog`.
- Ports: in `SearchItineraries`, `GetCatalog`; out `ItinerarySearchQuery`, `CatalogQuery`. The Redis cache is a
  decorator of the out ports in `infrastructure/adapter/out/cache` (the use case never sees Redis); PostgreSQL code
  in `infrastructure/adapter/out/persistence`. ArchUnit: Redis/Lettuce types only in the cache adapter (and the composition root that hands it a `StringRedisTemplate`).
- Schema: a single-row `data_version` table, incremented by the ingestion persistence adapter in each transaction
  that changes `itinerary` or `day_snapshot`; the search indexes.

## Non-functional requirements

- **Performance:** a first page under 50 ms and page 1,000 within the same order of magnitude at 1M rows (uncached,
  server side, measured in AC-23); a cache hit costs one PK read (data version) plus one Redis `GET`.
- **Cache:** key `search:v1:<dataVersion>:<normalized filters>:<limit>:<cursor position>` (and `catalog:v1:
  <dataVersion>`); value: the page as JSON; TTL 10 min (safety net; covers one ingestion interval); command timeout
  100 ms; Redis `maxmemory 256mb` + `allkeys-lru` in compose. After repeated failures a Resilience4j circuit breaker
  skips Redis for 30 s, so a blackholed Redis does not add the timeout to every request.
- **Observability:** Micrometer counters `search.cache` tagged `result=hit|miss|error|bypassed` (bypassed: breaker open); actuator exposes `metrics`.
  Redis health is reported by `/actuator/health` but not by readiness (the app works without it).
- **Security:** parameters are bound to SQL placeholders, never concatenated; cursors are validated before use.
- **Tests:** a shared Redis Testcontainer next to the shared PostgreSQL one; images as in compose.

## Open questions

All resolved (2026-11-06):

- [x] Q1 Date range — `400` outside [today − 1, today + 365] (AC-15).
- [x] Q2 Departed items on cached pages — dropped by the cache decorator; the contract states a cached page may be
  shorter than `limit`, even empty, while `hasMore` is `true` (AC-20, *Paging rule for clients*).
- [x] Q3 Cursor integrity — unsigned, with the filter fingerprint; rationale in ADR-0021.
- [x] Q4 Count estimate — left out.
- [x] Q5 Data version — single-row `data_version` table bumped in the writing transaction (ADR-0022).
- [x] Q6 Catalog — includes `routes` (origin → destinations) now (AC-17).
- [x] Q7 Redis circuit breaker — Resilience4j, skip Redis for 30 s after failures (ADR-0022).
- [x] Q8 Dependencies — springdoc-openapi 3.x (fallback: hand-written YAML), `spring-boot-starter-data-redis`,
  Redis Testcontainer.
- [x] Q9 Origin = destination — `200` with no items.
- [x] Q10 Indexes — a bounded in-memory sort is accepted where the matching set is small and measured; justified
  with `EXPLAIN` in ADR-0020; the worst realistic case (busiest route, no date, high `maxPrice`) is in the
  performance test (AC-22, AC-23).
