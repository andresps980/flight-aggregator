# SOLUTION

The write-up required by the [assessment statement](docs/exercise.md) (section 4). This page states each decision
and its result, and links to the [ADRs](docs/adr/README.md) for the reasoning,
[docs/architecture/](docs/architecture/overview.md) for the structure and [docs/ai/](docs/ai/README.md) for the AI
sessions instead of repeating them.

## Executive summary

**What it is.** Five containers started by `docker compose up --build`: a React dashboard behind nginx, the
`flight-aggregator` (Spring Boot 4, Java 21), a `gds-mock` serving Mock-Alpha (JSON), Mock-Beta (XML) and Mock-Gamma
(CSV) with latency, random `500`s and an outage switch, PostgreSQL and Redis
([system view](docs/architecture/overview.md#system-view)).

**How it works.** Ingestion is **decoupled from search**: a scheduled (every 15 min) or on-demand run fetches the
three providers in parallel through streaming parsers, with retries and a circuit breaker per provider. It
deduplicates in the framework-free domain (cheapest offer, 5 % markup, dead legs and invalid connections dropped),
one horizon day at a time, and replaces each day atomically in PostgreSQL with `COPY`. Search never calls a
provider: it pages the stored itineraries with a **keyset cursor** on `(final_price, id)`, served by two composite
indexes, through a Redis cache that is optional at runtime ([flows](docs/architecture/flows.md)).

**Headline results** (details and conditions in section 2):

| | |
|---|---|
| Deepest page, 1M rows, keyset vs `OFFSET` | page 1,000: **0.55 ms vs 50 ms**; last page (50,001): **0.51 ms vs 1.1 s** |
| Full 1M ingestion in compose | **21.5 s** first load, 29.5 s replacement, at the shipped `-Xmx768m` (container peak ≈ 1 GB); ≈ 250 MB working set per day in flight |
| Search, server side, first page | 0.05–2.9 ms for the common filter combinations (one rare case, origin + carrier + low `maxPrice`, ≈ 45–90 ms) |
| One provider down | run `PARTIAL`, provider `FAILED` with breaker `OPEN`, the previous complete day kept, search unaffected — **checked in CI on every PR** |
| `docker compose up --build`, clean clone | build ≈ 22 s, healthy 18 s later, first ingestion `COMPLETED` 21 s after that: **≈ 1 min** on a laptop, base image pulls aside |

**How it was built.** Spec-driven, test-first, with Claude Code under versioned guardrails (rules, skills, review
agents, hooks), every prompt and review finding logged; 7 specs, 24 ADRs, 13 merged PRs (two Dependabot
major bumps declined), each merged only with green quality gates ([1.5](#15-ai-tools-and-the-agent-system)).

**Where to look.** [Architecture](docs/architecture/overview.md) · [ADRs](docs/adr/README.md) ·
[specs](specs/README.md) · [usage](docs/usage.md) · [measurements at 1M](docs/measurements/search-1m.md) ·
[AI log](docs/ai/README.md).

## 1. Architectural decisions and AI tools

### 1.1 Shape

Hexagonal architecture **by feature, then by layer** in each backend module
([ADR-0002](docs/adr/0002-hexagonal-architecture.md)): `domain` and `application` are plain Java; adapters and the
composition root live in `infrastructure`; use cases are wired as beans, never annotated. The rules are executable:
each module's `ArchitectureTest` (ArchUnit) fails the build if a dependency points outwards, a framework leaks into
the domain, PostgreSQL or Redis code leaves its adapter, or features form a cycle. `ingestion` and `search` never
import each other — they meet only in the PostgreSQL schema. Diagrams derived from the code (a test checks that every
type in the class diagrams exists in the package it names): [overview](docs/architecture/overview.md), [package pages](docs/architecture/packages/README.md),
[flows](docs/architecture/flows.md).

### 1.2 Decisions

| Decision | ADR |
|---|---|
| Hexagonal architecture organised by feature, framework-free domain and use cases | [0002](docs/adr/0002-hexagonal-architecture.md) |
| Spec-driven development: every change starts from a spec with Given/When/Then criteria | [0003](docs/adr/0003-spec-driven-development.md) |
| Quality gates: format, unit + IT tests, ArchUnit, coverage ≥ 80 % lines / 70 % branches, CodeQL, dependency review, full-stack smoke | [0004](docs/adr/0004-quality-gates.md) |
| Scheduled pre-ingestion + `POST /api/v1/ingestions` (`202`, `409` while running); search never calls providers | [0006](docs/adr/0006-scheduled-pre-ingestion.md) |
| PostgreSQL (Flyway, `JdbcClient`, no JPA) as source of truth, Redis as a disposable search cache | [0007](docs/adr/0007-postgresql-and-redis.md) |
| Keyset pagination ordered by `(final_price, id)` | [0008](docs/adr/0008-keyset-pagination.md) |
| Minimal React + TypeScript + Vite frontend served by nginx | [0009](docs/adr/0009-react-frontend.md) |
| Domain assumptions: what is an invalid connection, EUR only, single airline, 5 % `HALF_EVEN` markup, UTC date filter | [0010](docs/adr/0010-domain-assumptions.md) |
| Incremental deduplication keyed by a deterministic itinerary key, total-order tie-break | [0011](docs/adr/0011-incremental-deduplication.md) |
| One `gds-mock` app serving the three providers, no code shared with the aggregator | [0012](docs/adr/0012-single-gds-mock-application.md) |
| Mock data generated per request from per-itinerary seeds, streamed, never materialised | [0013](docs/adr/0013-stateless-seeded-streamed-generation.md) |
| Provider resilience: Resilience4j core, read timeout, attempt and run deadlines, per-provider breakers | [0014](docs/adr/0014-provider-resilience.md) |
| Atomic partitions through a per-attempt buffer | [0015](docs/adr/0015-atomic-partition-buffers.md) |
| Aggregate one horizon day at a time, statistics summed | [0016](docs/adr/0016-day-partitioned-aggregation.md) |
| Each day an atomic snapshot; a less complete day never wipes a more complete one (kept stale, at most 2 h) | [0017](docs/adr/0017-day-snapshot-replacement.md) |
| Bulk load with PostgreSQL `COPY` (1.8× faster than batched inserts at 1M) | [0018](docs/adr/0018-bulk-load.md) |
| No concurrent runs across instances: session advisory lock on a dedicated connection | [0019](docs/adr/0019-ingestion-run-lock.md) |
| Two search indexes, chosen by `EXPLAIN` on 1M rows | [0020](docs/adr/0020-search-indexes.md) |
| Opaque, unsigned keyset cursor with a filter fingerprint | [0021](docs/adr/0021-search-cursor.md) |
| Redis cache-aside as a decorator of the query ports, keyed by a transactional data version | [0022](docs/adr/0022-search-cache.md) |
| Dashboard "real time" by polling the latest run (30 s, 3 s while running), never auto-replacing results | [0023](docs/adr/0023-real-time-by-polling.md) |
| Frontend types generated from the OpenAPI description; Vitest + RTL + MSW; ESLint + Prettier | [0024](docs/adr/0024-contract-generated-client-and-frontend-toolchain.md) |

### 1.3 Per feature

Each feature's spec has its acceptance criteria, each mapped to tests; each docs/ai entry has the session, the review
findings and how they were resolved. What follows is what a reviewer is most likely to ask about.

**Core domain** ([spec 001](specs/001-itinerary-aggregation/spec.md),
[package pages](docs/architecture/packages/flight-aggregator/aggregation-domain-model.md)). Two kinds of bad input are
handled differently: *malformed* data (a 2-letter airport, a non-positive or non-EUR price) fails when the value
object is built and is counted per provider and reason; *well-formed but unsellable* itineraries (dead legs, invalid
connections: mixed airlines, arrival not after departure, unchained segments, a revisited airport, connection < 45 min,
layover > 24 h, already departed) are discarded with one `DiscardReason` each. Identity is the statement's: airline
and flight numbers, connections and departure times.

**Mock providers** ([spec 002](specs/002-gds-mock/spec.md)). Built to exercise the aggregator: cross- and
same-provider duplicates at different prices, one invalid itinerary kind per discard reason, malformed records, three
time representations (UTC epoch milliseconds, ISO-8601 with offset, local time + IANA zone). `GET /admin/world/stats` reports what spec 001 should produce,
so ingestion reconciles **exactly**. *Found:* a streamed response is dispatched twice by Spring, so latency was
applied twice and a `500` could follow a sent feed; `ConcurrencyIT` caught it (three feeds in parallel took the sum,
not the slowest).

**Ingestion and resiliency** ([spec 003](specs/003-provider-ingestion/spec.md),
[flow](docs/architecture/flows.md#ingestion-run)). Streaming parsers (Jackson token stream, StAX with DTDs refused,
a line reader), hostile input bounded. The resilience values follow from the mock's 100–5000 ms latency and 10 %
errors: a 6 s read timeout (below 5 s, ≈ 40 % of normal answers would be retried), 3 attempts (a partition is lost
with p ≈ 0.1 % instead of 10 %), a breaker that opens at ≥ 80 % failures of 10 calls (p ≈ 4 × 10⁻⁷ on that noise,
checked by a seeded 10,000-call test) and stays open 30 s — far below the 15 min interval, so the next run always
probes again. A failed partition never fails the run; a run is `FAILED` only if every provider failed. Below,
Mock-Beta forced down from the dashboard's demo controls and a run started with *Refresh data*: the run is `PARTIAL`,
Beta `FAILED` on all 7 partitions with its breaker `OPEN`, Alpha and Gamma `COMPLETED`; every day kept its previous,
more complete snapshot (`KEPT_STALE`), so search serves it with `STALE` badges instead of dropping Beta's offers.

![Data status after a Mock-Beta outage: run PARTIAL, mock-beta FAILED 0/7 with breaker OPEN, mock-alpha and mock-gamma
COMPLETED, demo controls with the Beta outage switched on](docs/images/dashboard-outage.png)

*Found:* interrupting the calling thread does not abort a blocked socket read on a platform thread, so each attempt
now runs on its own virtual thread; a waiting partition could start a request after the run deadline; an
`OutOfMemoryError` could leave a day's coordinator waiting forever; and the providers doc had a DST error the parser
test caught.

**Persistence and pipeline** ([spec 004](specs/004-persistence-and-pipeline/spec.md)). A day is the unit of
replacement: one transaction deletes its rows, `COPY`s the new ones, upserts `day_snapshot` and bumps the data
version. Runs never overlap across instances (advisory lock checked before every day is published; orphaned `RUNNING`
rows become `ABORTED`). `ANALYZE` after each run, retention at its start. *Found:* shutdown waited for a whole run
(Spring's scheduler lets tasks finish before beans are destroyed) — the schedule now stops on context close and the
interrupted run is stored `FAILED`; the lock connection was shared by concurrent day coordinators (found in review).

**Search and cache** ([spec 005](specs/005-search-api/spec.md),
[flow](docs/architecture/flows.md#search-request)). Filters `origin`, `destination`, `date`, `maxPrice`, `carrier`;
`limit` + `cursor`; a catalog of filter values; OpenAPI committed and checked. The cursor carries a filter
fingerprint, so a cursor reused with other filters is a `400`, not a silently wrong page. The cache decorates the
query ports — the use case cannot tell — and is invalidated by a version row bumped in every writing transaction, so
invalidation is atomic with the commit and nothing is deleted. Redis down means 100 ms timeouts, then a breaker skips
it for 30 s; search keeps answering from PostgreSQL. *Changed after review:* the catalog is memoised per data version
in the JVM (single-flight), so a version bump or a Redis outage costs one scan, not one per concurrent request.

**Dashboard** ([spec 006](specs/006-dashboard/spec.md), [docs/frontend.md](docs/frontend.md)). React 19 + TypeScript,
runtime dependencies `react` and `react-dom` only; filters in the URL; keyset "Load more"; live run status with
*Refresh data*; plain-SVG charts with table alternatives; demo controls to take a provider down. Types are generated
from `docs/api/openapi.json`, so the chain Java → JSON → TypeScript is checked at both links — which **found** a
springdoc bug that typed a nullable report as never null. *Found by hand, then guarded:* a grid layout that painted the
results over the form at 1170 px; a Playwright check at four widths now fails on it.

**Full-stack smoke test** ([spec 007](specs/007-stack-smoke/spec.md)). `scripts/stack-smoke.sh`, the same locally and
in CI, proves the statement's two stack-level promises on every PR: `docker compose up --build` boots healthy and
ingests (checked through nginx, plus the e2e reconciliation test against the running mock), and one provider dropping
out (a Mock-Beta outage) gives a `PARTIAL` run with Beta `FAILED`, its breaker `OPEN` and search still answering.
*Found:* `GDS_MOCK_PROFILE=deterministic` alone kept 10 % errors, because compose sets the error rate explicitly and
an environment variable beats a profile file; the docs had claimed otherwise.

### 1.4 SOLID, and what is deliberately not abstracted

| Principle | Where it shows |
|---|---|
| **SRP** | One class per validation rule; `Money` owns rounding, `MarkupPolicy` pricing, `OfferPreference` the tie-break. The snapshot policy decides, the publisher applies, the store runs SQL. Validation in the search domain, paging in the use case, SQL in the adapter, caching in a decorator. |
| **OCP** | A rule is a new `ItineraryRule`; a pricing scheme a new `MarkupPolicy`; a provider a new `FeedParser` (aggregator) and `ProviderFormat` (mock); the cache was added without touching the use case or the SQL adapter. A new rule also needs a `DiscardReason` constant — a deliberate, type-safe trade-off. |
| **LSP** | Port and interface Javadocs state contracts (a rule returns empty or one reason and never throws; a markup policy returns EUR at scale 2, never below the base). The cache decorators honour the query ports' contract; one abstract contract test runs for every provider format. |
| **ISP** | One-method `ItineraryRule` / `MarkupPolicy`; `DaySnapshotStore` (day writes) and `SnapshotMaintenance` (retention, statistics) are separate ports implemented by one adapter; `DataVersionQuery` is used only by the decorators. |
| **DIP** | The application owns every port; infrastructure implements them; `Clock`, executors and randomness are injected, so time-dependent rules and chaos are tested without sleeping. |
| **Not abstracted** | `ItineraryValidator`, `OfferAggregator`, key derivation: one implementation, no variation point. gds-mock has no `application` layer (no use cases, no driven ports). The scheduler uses Spring's `TaskScheduler` directly (it is an adapter). The report's JSON mapping is shared by storage and API, so the API shows what is stored. The cache codec serialises the search records directly (a versioned key prefix instead of a cache-only DTO layer). The frontend has no repository/service layers or DI — at its size they would be ceremony. |

### 1.5 AI tools and the agent system

The code was written with **Claude Code** (Claude Opus 5.5) under guardrails versioned in this repository
([ADR-0005](docs/adr/0005-ai-assisted-development.md)), so anyone who clones it gets the same agent behaviour:

| Piece | What it does |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Project rules: architecture and layering, testing conventions, git workflow, definition of done, English-only |
| Skills (`.claude/skills/`) | `spec-feature` (requirement → spec with Given/When/Then and *open questions*, never invented requirements), `implement-spec` (branch → tests first → code → docs), `adr`, `pr-description` |
| Agents (`.claude/agents/`) | `code-reviewer` (SOLID, hexagonal rules, tests, security), `spec-reviewer` (acceptance-criteria traceability, scope creep), `test-engineer` (coverage gaps) |
| Hooks (`.claude/settings.json`) | Block commits/pushes to `main`, force-push and `--no-verify` (with its own test suite in CI); Spotless / Prettier after every edit; `./mvnw verify` and/or the frontend checks before the agent may finish a turn, for whatever changed |
| Git hooks + GitHub rulesets | The same gates for humans: pre-commit format check, pre-push verify; `main` changes only through PRs with green required checks |

**The workflow per PR:** the author's prompt → the agent reads the repo and writes the spec with open questions →
the author answers and approves → tests first (committed red on purpose), then the implementation → `code-reviewer`
and `spec-reviewer` agents → the author decides which findings to fix → PR, CI by commit SHA. The **human owns every
decision**: specs are approved, review findings triaged and PRs merged by the author. Each [docs/ai](docs/ai/README.md)
entry records the verbatim prompts, the agent's questions and the answers, the review findings with their outcome,
and what was found while implementing.

**The guardrails also constrain the agent.** In PR 8 the agent tried to register a new hook and `npm` permissions in
`.claude/settings.json`; Claude Code's classifier blocked it as self-modification, so the author applied the change
by hand ([docs/ai/008](docs/ai/008-dashboard.md)).

**History.** PRs are merged with merge commits, not squash, so `main` keeps the spec-driven sequence: a `docs:` commit
with the spec and ADRs, a `test:` commit with the tests written first (intentionally red), then the implementation
and review fixes. Every merge result is green.

## 2. Data modelling and pagination up to 1,000,000 rows

### 2.1 Domain model and deduplication

- **Times are `Instant`s**; connection and layover times are computed on absolute time, so time zones cannot skew
  them. Adapters convert provider local times with their offsets or zones.
- **Money** is a `BigDecimal` in EUR at scale 2, rounded `HALF_EVEN` once when created; the markup is computed exactly
  and rounded once (no double rounding). Untrusted amounts are bounded, so input like `1E999999999` is rejected
  cheaply.
- **Deduplication key** — a readable, deterministic `ItineraryKey` such as
  `IB3456:MAD-BCN@2026-11-10T08:00:00Z|IB3457:BCN-LHR@2026-11-10T10:30:00Z` (flight numbers, airports, departure
  instants). It is also the unique key of the `itinerary` table.
- **Selection** uses a total order — lowest base price → provider id → earliest arrivals → canonical form — so the
  winner never depends on which provider answered first ([ADR-0011](docs/adr/0011-incremental-deduplication.md)).
- **"Now" is read once per run**, so offers that arrive late in a long run are not judged against a later clock.

### 2.2 Memory at 1M: aggregate one day at a time

`OfferAggregator` keeps one entry per distinct valid itinerary (the preferred offer and its sorted providers), not
one per offer, and accepts offers one at a time from the streaming parsers: about **1.2 KB per itinerary**, so the
whole 1M world would need ≈ 1–1.2 GB. Ingestion never holds the world: duplicates cannot cross days (the identity
includes departure instants and providers partition by UTC departure date), so it runs **one aggregator per horizon
day**, at most 2 days in flight, and sums the additive statistics
([ADR-0016](docs/adr/0016-day-partitioned-aggregation.md)). Each partition is fed to its day's aggregator as soon as
it arrives and dropped.

Measured at 1M (gds-mock deterministic, 1,840,053 offers → 946,092 itineraries; Apple M1 Max):

| | Result |
|---|---|
| Working set | ≈ 250 MB per day in flight (better than ADR-0016's ≲ 0.5 GB estimate) |
| Smallest heap that completes, ingestion → PostgreSQL | 512 MB (384 MB: out of memory, the container restarts and the next run marks the cut run `ABORTED`) |
| Shipped heap | **`-Xmx768m`** (1.5× the floor; container peak ≈ 1 GB). It was 1,536 MB while results were kept in memory (spec 003), before PostgreSQL took them day by day |
| Too little heap | degrades before it fails: GC pauses become read timeouts and the run is `PARTIAL`, not a crash |
| Full run in compose, 946,044 itineraries, search indexes present (2026-11-07) | **21.5 s** first load, **29.5 s** replacement (7 days `REPLACED` each), container peak 0.99 GiB |
| Days in flight | 1 / 2 / 3 / 7 days: 11.4 / 8.1 / 7.4 / 8.2 s with 256 / 512 / 768 / 1,024 MB (ingestion alone, results discarded): 2 is as fast as 3 or 7 with half or a quarter of their memory |

### 2.3 Read model in PostgreSQL

One **denormalized row per aggregated itinerary** in `itinerary`
([schema](docs/architecture.md#schema), [ADR-0007](docs/adr/0007-postgresql-and-redis.md)):

- **Every filter and the sort key are scalar columns of the same row** (`origin`, `destination`, `airline`,
  `final_price`, `departure_date`, `departure_at`), so a composite B-tree answers a filtered, price-ordered page with
  one index range scan and `LIMIT`, without a join.
- **`id bigint` identity** is the keyset tie-breaker with `final_price`.
- **Segments as JSONB**: they are only displayed, so a `segment` table (2–3× the rows) and a join on every read buy
  nothing.
- **`departure_date`** (the provider partition) is the unit of snapshot replacement and retention — one indexed
  `DELETE` each; `day_snapshot` records per day whether it is complete, which providers are missing and since when it
  is stale, shown to users as freshness badges; `ingestion_run` keeps each run's report as JSONB.
- **Prices `numeric(12,2)`** in EUR; `Money` caps amounts at 9 integer digits, so the column has room to spare.
- **Bulk load with `COPY`** (text format, streamed from a 64 KiB buffer): 1M rows in 10.4 s vs 18.9 s with batched
  `INSERT`s and `reWriteBatchedInserts` (31.0 s without) — [ADR-0018](docs/adr/0018-bulk-load.md). With the two search
  indexes the first load takes 17.9 s and a full replacement 32.6 s.
- **Size at 1M:** 623 MB with every index after `VACUUM FULL` — 455 MB rows + 168 MB indexes, of which the two search
  indexes are 65 MB (ADR-0020 estimated 30–60 MB); 690 bytes per row. Right after a replacement, dead tuples double
  it (1.25 GB after two loads) until autovacuum reclaims them (see 3.1).
- **Partitioning by `departure_date`** was considered and deferred: instant retention and no dead tuples, at the cost
  of DDL on every run; at ≈ 1M rows plain deletes and autovacuum are enough ([ADR-0017](docs/adr/0017-day-snapshot-replacement.md)).

### 2.4 Indexes, chosen by `EXPLAIN` on 1M rows

Two indexes beyond the primary and unique keys ([ADR-0020](docs/adr/0020-search-indexes.md),
[full plans](docs/measurements/search-1m.md)): `(final_price, id)` for searches without a route, and
`(origin, destination, final_price, id)` for a route. Every measured filter combination is an index scan in keyset
order stopped by `LIMIT` — no sequential scan, no sort — and the spec's common combinations (AC-22) run in
**0.05–2.9 ms** server side for the first page. The data's shape decides what else is cheap: with ≈ 35 airports and
24 airlines, a filter on the price index finds a page quickly (a `date` first page skips 106 entries). The one slow
case is two selective filters outside the route index (origin + carrier + a low `maxPrice`, 365 matching rows):
≈ 45–90 ms, accepted rather than adding indexes that slow every load; the cache serves its repeats in ≈ 3 ms.

### 2.5 Keyset vs `OFFSET`, measured

Same filters, page of 20, server side, median / p95 of 30 runs on 1,000,006 rows (`SearchPerformanceIT`,
[full table](docs/measurements/search-1m.md)):

| Filters (matching rows) | Page | Keyset | `OFFSET` |
|---|---:|---:|---:|
| none (1,000,006) | 1 | 0.75 / 1.24 ms | 0.61 / 0.76 ms |
| none | 1,000 | **0.55 / 0.75 ms** | **50.30 / 68.81 ms** |
| none | 50,001 (last)¹ | **0.51 / 0.66 ms** | **1,116.75 / 1,128.32 ms** |
| `date` (142,858) | 1,000 | 0.47 / 0.63 ms | 63.68 / 65.51 ms |
| `carrier` (42,082) | 1,000 | 0.76 / 0.94 ms | 159.11 / 176.64 ms |
| `origin` (29,375) | 1,000 | 0.89 / 1.78 ms | 94.91 / 104.69 ms |
| `carrier` + `maxPrice` (12,397) | 620 (last) | 0.99 / 1.22 ms | 174.92 / 192.35 ms |
| busiest route, `maxPrice` above all (993) | 50 (last) | 0.53 / 0.91 ms | 2.14 / 2.98 ms |

¹ Added on 2026-11-07 by a re-run of the same test ([addendum](docs/measurements/search-1m.md#addendum-2026-11-07-the-last-page));
the other rows are from the 2026-11-06 run and were within noise in the re-run.

- **Keyset cost does not depend on depth**: the query seeks the cursor's position in the index and reads
  `limit + 1` entries, so the last page costs what the first does.
- **`OFFSET` grows linearly with depth**: page 1,000 reads and discards 19,980 rows first (≈ 90× slower), and with a
  residual filter every discarded row is also a heap fetch (carrier: ≈ 200×). On the last page of the unfiltered
  result it reads and discards a million index entries: **1.1 s against 0.5 ms**, ≈ 2,200×.
- **`OFFSET` is not even correct here**: every run replaces whole days, so a row inserted or deleted before the
  current position shifts every later page (duplicates or gaps); keyset continues from a position.
- **The price of keyset:** no "jump to page N" and no total count (an exact `COUNT(*)` would read up to 1M rows per
  request); the dashboard pages forward with "Load more". A cursor taken before a day's replacement continues from its
  `(price, id)` position in the new data (see 3.1).

### 2.6 Cache

Keys are `v1 · data version · normalized filters · limit · position`, values are pages, TTL 10 min only as a safety
net ([ADR-0022](docs/adr/0022-search-cache.md)). Measured through the API at 1M rows (median, client side): typical
searches **2.6–8 ms uncached / 1.8–5.4 ms cached** — PostgreSQL is already sub-millisecond thanks to the indexes, so
the cache mostly takes repeated load off the database; it pays off for the expensive requests: the **catalog**
(420 ms grouped scan → 1.4 ms, ≈ 300×) and the two-filter combination no index serves (56 → 3.0 ms, ≈ 18×).

### 2.7 Other measured results

| | Result |
|---|---|
| gds-mock, full 1M feed in one request | Alpha 2.7 s (144 MB JSON) · Beta 10.6 s (332 MB XML) · Gamma 2.8 s (100 MB CSV); the three in parallel 11.4 s, ≈ the slowest; container ≈ 229 MiB with a 256 MB heap |
| Reconciliation | Every figure of the report equals gds-mock's `/admin/world/stats` (per provider, reason and day) — in the build against recorded feeds, by hand at 100k and 1M, and in CI on every PR (smoke) |
| Default compose stack (100,000, chaos on), clean clone | build 22 s (Maven cache mount pruned, base images local), all five services healthy 18 s after `up`, first scheduled run `COMPLETED` in 18.3 s (94,503 itineraries) — 61 s from `build` to searchable data; every quick-start URL answered |
| Full-stack smoke in CI | 15 checks in 2 min 17 s with an empty layer cache (build 74 s), 1 min 21 s with it warm (build 14 s); on `main` after the PR 14 merge (`3c2dc3b`) 2 min 13 s — `main` cannot read PR caches |
| `./mvnw verify` (both modules: format, unit and integration tests, ArchUnit, coverage gate) | **67 s** on an Apple M1 Max: flight-aggregator 509 unit + 182 integration tests, gds-mock 146 + 41 |
| Frontend | `npm run check` **7 s**: 118 tests, 99.7 % lines / 95.2 % branches (gate 80 / 70); bundle 76 kB gzipped JS; image smoke test 23 checks |

## 3. System limitations and trade-offs

### 3.1 Freshness and consistency

- **Search data is up to one ingestion interval old** (15 min by default) — the price of decoupling search from
  provider latency ([ADR-0006](docs/adr/0006-scheduled-pre-ingestion.md)).
- **Partial days favour completeness over freshness**: while a provider is missing, a day keeps its previous snapshot
  (stale badge) for up to 2 h, ignoring the other providers' fresher prices for that day. A per-provider merge would be
  fresher but cannot be exact, because rows keep only the selected offer ([ADR-0017](docs/adr/0017-day-snapshot-replacement.md)).
- **Pagination across a refresh**: a replaced day gets new ids, so a walk that spans a run may skip or repeat an
  itinerary at the boundary and see new prices. Each page is consistent; rejecting old cursors would send users back
  to page 1 after every run.
- **Every run invalidates the whole cache**, including unchanged days; and every request reads the data version from
  PostgreSQL, so PostgreSQL must be up even for cache hits.
- **Cached pages can be short**: items departed since caching are dropped, so a page can have fewer than `limit`
  items (even none) while `hasMore` is true; the contract and the dashboard keep paging.
- **The catalog is a grouped scan** (≈ 420 ms at 1M), once per data version per instance; a run bumps the version once
  per day it writes, so up to 7 scans per run.
- **Dead tuples**: replacing up to 7 days per run leaves dead rows until autovacuum catches up (1.9 M dead next to
  0.95 M live after two 1M replacements in a row) — disk and some scan efficiency, not correctness.

### 3.2 Pagination and search

- No total count, no page N, no backwards cursor, one sort order (price); another order needs its own index and
  cursor version ([ADR-0008](docs/adr/0008-keyset-pagination.md)).
- Cursors are not signed: a client can start anywhere (the data is public). Cache keys are client-controlled, so a
  client looping over crafted positions fills Redis, bounded by `maxmemory 256mb` + LRU.
- Selective filters outside the indexes scan more of the price index (measured cases in ADR-0020).
- Times are shown in UTC: the API has no airport time zones.

### 3.3 Domain assumptions

- EUR only, single-airline itineraries, fixed 45 min / 24 h connection thresholds, one-way only (a revisited airport,
  including a return to the origin, is discarded) — [ADR-0010](docs/adr/0010-domain-assumptions.md).
- An itinerary breaking several rules is counted under its first failing reason only, so the counts add up.
- Arrival times are not part of the identity: if providers disagree for the same flight, the earliest arrival is kept.
- Flight numbers are canonical without leading zeros (`IB0123` = `IB123`).
- Day partitioning assumes providers partition by the UTC date of the first departure (true for gds-mock); one that
  did not would produce a duplicate across days, never a lost offer.
- **Time-zone rules must agree between provider and aggregator.** CI found a real case: tzdata 2026 moved
  `Africa/Casablanca` from UTC+1 to UTC+0, so fixtures recorded on an older JDK stopped reconciling. gds-mock now uses
  only zones whose rules are stable (checked by a test); with real providers, keep JDKs current, prefer offsets or UTC
  and compare the same flight across providers.

### 3.4 Ingestion and resilience

- A retry re-downloads the whole partition (no resume, no disk spooling); a partition is buffered until its attempt
  completes.
- Breaker state is per JVM and in memory: a restart closes every breaker; replicas do not share state.
- `HttpURLConnection` silently re-sends a GET once on a connection reset; that request is not counted in the report.
- An attempt aborted by a deadline frees its thread once the socket closes, so a run can overrun its deadline by up to
  one read timeout.
- Any non-5xx error status is a `CLIENT_ERROR`: not retried, not a breaker failure.
- `COPY` has no per-row errors: one bad row fails its day (`WRITE_FAILED`), which keeps its previous snapshot.
- The run lock is a PostgreSQL session lock: it needs a direct connection or a session-mode pooler (not PgBouncer in
  transaction mode) and holds one extra connection for up to 10 min. A lost lock is detected at the next day boundary;
  each day stays atomic, but there is no fencing token in the write itself ([ADR-0019](docs/adr/0019-ingestion-run-lock.md)).
- Keep `maximum-pool-size` ≥ `max-concurrent-days` + 2, or publishes wait for connections.
- Runs are not cancellable; history keeps 7 days; a run cut by a crash stays `RUNNING` until the next run aborts it
  (≤ 15 min). Graceful shutdown waits up to 30 s for a run to store its `FAILED` state.

### 3.5 Security and operations

- **Unauthenticated, not rate limited**: the API, the ingestion trigger, `/actuator/metrics`, the dashboard's demo
  controls (no CSRF protection) and gds-mock's admin API. Acceptable for a local demo stack; see section 4.
- API failure messages are generic (no exception text, which may contain SQL or host names); details are logged under
  the run id.
- `npm audit`: 7 moderate advisories in `minimatch` inside the dev-only lint toolchain; production dependencies audit
  clean. The author dismissed the Dependabot alert as *risk is tolerable* (dev-only, transitive, not shipped, no
  compatible fix yet) — [docs/ai/009](docs/ai/009-dependabot-triage.md#open-item-for-the-author--resolved).
- TypeScript 5.9 and ESLint 9 are held behind the latest majors until the tools' peer ranges accept them; Dependabot
  ignores those majors with a revisit condition ([docs/frontend.md](docs/frontend.md#version-constraints)).

### 3.6 The mock

- It regenerates the world on every request (CPU instead of memory; cheap at this volume); records come in generation
  order, not shuffled.
- The three providers share one JVM, so a GC pause or crash affects all three at once; real providers fail more
  independently.
- Failures happen before the first byte only: no truncated streams, gzip or trickling bodies.
- No catch-all exception handler (it would turn framework errors and client disconnects into `500`s); determinism holds
  per version of the generator.

### 3.7 Testing

- No browser end-to-end flows: behaviour is tested in jsdom with MSW, layout in headless Chromium at four widths, the
  image with a smoke test, and the stack over HTTP by the smoke script.
- The smoke test runs the deterministic profile on 20,000 itineraries (chaos would make the first run's status
  random); chaos and volume are covered by the spec 003/004 tests and the 1M measurements. It does not wait for the
  breaker's half-open recovery (≥ 30 s; covered by ITs), and it needs the stack's ports free.
- The e2e test and gds-mock both use today's UTC date: a run across midnight UTC can fail its reconciliation.

## 4. What would change for production

| Area | Today | Production |
|---|---|---|
| Access | Everything unauthenticated | OAuth2/OIDC at the edge; ingestion trigger and admin behind a role; rate limiting per client at the gateway; `DEMO_CONTROLS` off; `Origin` checks on state-changing requests |
| Management | `/actuator/*` on the application port | Separate management port, not exposed publicly |
| Secrets and config | Local credentials in compose | Secrets manager; per-environment configuration |
| Scale-out | One aggregator instance (the lock already allows several) | Several instances behind a load balancer; breaker state shared or per-instance by design; Redis with persistence off but HA |
| Data | Plain table, autovacuum defaults | Day partitions (instant retention, no dead tuples) or autovacuum tuned for the table; read replica for search; backups |
| Freshness | Polling every 30 s | SSE for run events ([ADR-0023](docs/adr/0023-real-time-by-polling.md)); per-day cache versions |
| Providers | Full feed per day, re-downloaded on retry | Incremental or delta feeds where providers offer them; resumable downloads; per-provider rate limits |
| Delivery | `docker compose` | Container images built once in CI and promoted; Kubernetes with readiness/liveness from the existing probes; Flyway migrations as a separate job |

### 4.1 Observability

**What exists today.**

- **Health:** Actuator liveness and readiness probes on both services (used by the compose healthchecks); readiness
  does not depend on Redis, by design.
- **Metrics** (`/actuator/metrics` on the aggregator, Micrometer; read from the running stack): Spring Boot's
  auto-configured meters — `http.server.requests` (count and total time per URI template, status and outcome, no
  percentile histograms), `jvm.*` (memory, GC pauses, threads), `hikaricp.connections.*` (active, pending, acquire
  and usage time, timeouts), `jdbc.connections.*`, `lettuce` (Redis command latency), `executor.*`, `tomcat.sessions.*`,
  `logback.events` (log lines per level), `process.*` / `system.*`. gds-mock exposes `health` and `info` only. The only custom meter
  is the counter `search.cache` with `result=hit|miss|error|bypassed` — `error` counts failed Redis calls and `bypassed`
  the calls skipped while the cache breaker is open, i.e. the Redis fallback rate.
- **Persisted ingestion reports:** every run's report is stored as JSONB and served by `GET /api/v1/ingestions/{id}`:
  status per provider, partitions succeeded and failed with `FailureKind` and attempts, the breaker state at the end,
  offers received and malformed records per reason, and per day the outcome (`REPLACED`, `KEPT_STALE`, …), the
  missing providers and the deduplication statistics. The dashboard's status panel is built on it.
- **Logs:** plain text to stdout; one summary line per run (status, itineraries, offers, discards, duplicates,
  duration, per provider `status(partitions, breaker)`), WARN on retention or statistics failures and orphaned runs,
  the cache breaker logged once when it opens.

**What is missing**: no time series of ingestion health (the reports are per run, not aggregated), no Resilience4j
metrics (the breakers are not bound to Micrometer), no latency histograms with percentiles, no correlation id across
nginx → aggregator → providers, no alerting.

**Plan.**

1. **Metrics → Prometheus + Grafana.** Add `micrometer-registry-prometheus` and expose `/actuator/prometheus` on the
   management port. New meters, recorded in the adapters and the run service (the use cases stay framework-free:
   a small `IngestionMetrics` port with a Micrometer adapter):
   - Ingestion: `ingestion.run.duration` (timer, tag `status`), `ingestion.partition.duration` and
     `ingestion.partition.failures` (tags `provider`, `kind`), `ingestion.attempts` / retries (tag `provider`),
     `ingestion.malformed` (tags `provider`, `reason`), `ingestion.discarded` (tag `reason`),
     `ingestion.days` (gauge, tag `outcome`, including `stale` and the age of the oldest stale day), plus
     `resilience4j-micrometer` for breaker state, calls and transitions per provider.
   - Search: `http.server.requests` with percentile histograms (p50 / p95 / p99) per URI template; cache hit ratio
     from `search.cache` (`hit / (hit + miss)`); Redis fallbacks (`error`, `bypassed`) and the cache breaker's state.
   - Platform: JVM memory and GC pauses (the ingestion heap is the tight resource), HikariCP pool usage and wait time
     (the pool must cover days in flight + API), PostgreSQL via `postgres_exporter` (dead tuples, autovacuum), Redis
     via `redis_exporter` (memory, evictions).
   - Grafana: an *Ingestion* dashboard (run duration and status over time, per-provider failures by kind, retries,
     breaker state timeline, malformed by reason, stale days) and a *Search* dashboard (latency percentiles per
     endpoint, cache hit ratio, fallback rate, pool saturation).
2. **Structured logs → Loki.** Spring Boot's structured logging (`logging.structured.format.console=ecs` or
   `logstash`) for JSON lines with `traceId` / `spanId` and the run id in the MDC, shipped by Promtail or the
   OpenTelemetry Collector to Loki; Grafana links a trace to its logs and a run id to everything it did.
3. **Tracing → OpenTelemetry.** Micrometer Tracing with the OTLP exporter in both Spring services; nginx with the
   OpenTelemetry module so the trace starts at the edge (W3C `traceparent` forwarded through `/api`); spans per
   ingestion run, per day and per provider attempt (retries and breaker rejections visible), JDBC and Redis spans on
   search; Tempo or Jaeger as the backend.
4. **Alerting and SLOs** (Prometheus rules → Alertmanager):

   | Alert / SLO | Condition |
   |---|---|
   | Provider breaker open | a provider's breaker `OPEN` for more than N minutes — N ≈ 30 (two consecutive runs: it only probes again when the next run calls it) |
   | Ingestion failing | a run ended `FAILED`, or no `COMPLETED`/`PARTIAL` run for more than 2 intervals |
   | Data too stale | a day stale for more than 1 h (at `max-stale-age`, 2 h, it is replaced by the incomplete data), or any day `INCOMPLETE` |
   | Cache degraded | `search.cache` error + bypassed rate above a threshold for 10 min (search still works, but PostgreSQL carries all load) |
   | Search latency SLO | p95 of `/api/v1/itineraries` < 100 ms and p99 < 300 ms over 30 days; error budget alerting on burn rate |
   | Availability SLO | 99.9 % of search requests non-5xx over 30 days |
   | Pool saturation | HikariCP pending connections > 0 for 5 min |
