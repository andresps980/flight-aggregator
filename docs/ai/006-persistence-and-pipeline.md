# 006 — Persistence and the ingestion pipeline: PostgreSQL day snapshots, runs, scheduling, compose (spec 004)

- **Date:** 2026-11-06
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `feat/004-persistence-and-pipeline` (PR 6 of the repository)

## Prompts

### 1. Kick-off

````text
We continue the assessment. Read CLAUDE.md, docs/exercise.md, SOLUTION.md, docs/adr/, docs/ai/
and specs/ (especially the follow-ups recorded in specs 002 and 003) before planning. Same
conventions as previous PRs: everything in English, SDD workflow, SOLID applied pragmatically,
record this session in docs/ai/, update SOLUTION.md where relevant. This is PR 6 in the repo.
PRs are merged with merge commits (not squash) to keep the test-first history visible: add a
line about this to SOLUTION.md, noting that test-first commits are intentionally red.

Goal: persistence and the end-to-end ingestion pipeline — PostgreSQL replaces the in-memory
result store, ingestion runs on a schedule and on demand via REST, and a first
docker-compose with postgres + gds-mock + flight-aggregator. No search API, Redis or
frontend yet (next PRs), but the schema must be designed for the search that comes next.

1. Run /spec-feature to write specs/004-persistence-and-pipeline/spec.md. Cover at least:
   - Schema (Flyway migrations) as a denormalized read model for search over up to ~1M rows:
     one row per aggregated itinerary with itinerary key (unique), origin, destination,
     departure date (UTC date of first departure), first departure / last arrival instants,
     airline, number of stops, base and final price (numeric), selected provider, providers
     that offered it, segments (e.g. JSONB, avoiding joins on read), ingestion run id,
     timestamps. A bigint id usable as keyset tie-breaker with price. Search indexes can be
     finalized in the search PR, but justify the shape now.
   - Day snapshot semantics, honouring the spec 003 follow-up: a day where every provider
     succeeded atomically replaces that day's rows (readers see either the old or the new
     snapshot, never a mix). A day missing a provider must not wipe that provider's previous
     good data — I suggest: if the day already has a snapshot, keep it and mark it stale in a
     day-status table; if it has none, publish the partial day marked incomplete. Propose
     alternatives (e.g. merge strategies, partitioning by departure date) and justify in an ADR.
   - Fast bulk load: e.g. PostgreSQL COPY (PgJDBC CopyManager) or JdbcClient batching with
     reWriteBatchedInserts — measure and choose; target loading 1M rows in well under a minute.
     PostgreSQL-specific code stays in the persistence adapter.
   - Retention: days before today are removed on each run.
   - Ingestion runs persisted (id, status, start/end, full report as JSONB, per-day status)
     so they can be queried later and by the dashboard.
   - Scheduling: configurable fixed delay (15 min default) and an initial run shortly after
     startup, so data appears soon after `docker compose up`.
   - No concurrent runs, also across instances (e.g. pg_try_advisory_lock), not just in-memory.
   - REST: POST /api/v1/ingestions → 202 Accepted + Location + run id (runs asynchronously on
     a virtual thread); if a run is in progress → 409 ProblemDetail including the running id.
     GET /api/v1/ingestions/{id} and GET /api/v1/ingestions/latest return status and report.
   - Docker: docker-compose.yml with postgres (named volume, healthcheck), gds-mock and
     flight-aggregator (depends_on service_healthy, config via env). Revisit the aggregator's
     -Xmx now that results no longer live in memory; measure and justify the new value.
   Show me the spec with open questions and wait for my approval.

2. Design guidance (justify in the plan; /adr for snapshot strategy, bulk load technique and
   run locking): persistence adapter in infrastructure/adapter/out/persistence implementing
   the existing sink port plus a port for ingestion runs; the ingestion use case stays
   framework-free; scheduling and the REST controller are inbound adapters. Decide whether
   the in-memory store remains as a test double or is removed.

3. After I approve the spec, /implement-spec 004 on feat/004-persistence-and-pipeline.
   Tests first:
   - Persistence ITs with Testcontainers PostgreSQL (Spring Boot @ServiceConnection, one
     shared container to keep the build fast): migrations apply; full-day replacement is
     atomic (concurrent reader never sees a mix); partial day keeps previous data and marks it
     stale; partial day without previous data is published as incomplete; retention; bulk
     load correctness (every field round-trips, including segments and prices).
   - Run lock: second POST while running → 409; two concurrent triggers → one run.
   - REST ITs for POST/GET ingestions (202 + Location, 409, 404 for unknown id).
   - End-to-end IT: WireMock feeds (the recorded fixtures) → ingestion → PostgreSQL → rows
     and persisted report reconcile with the expected figures.
   - Scheduler: initial run and fixed delay with small test values (no long sleeps).
   After writing the tests (before implementing), stop and show me the list grouped by AC.

4. Run code-reviewer and spec-reviewer, fix findings, record them in docs/ai/. Update
   docs/architecture.md (pipeline + schema), docs/usage.md (ingestion endpoints, config,
   docker compose), README quick start (compose now partially available), SOLUTION.md
   (data model justification, snapshot strategy, bulk load numbers, limitations).

5. Verify: ./mvnw verify green (report duration); docker compose up --build reaches healthy,
   the initial ingestion completes and rows appear; a manual 1M run (gds-mock volume 1M)
   with load time and heap recorded in SOLUTION.md. Then /pr-description, and after pushing
   check CI and code scanning findings as before.
````

### 2. Spec approval with changes

````text
Approve as proposed, with these notes:

- Q3: also run ANALYZE on the itinerary table after each run's writes, so the planner's
  statistics stay current for the search PR.
- Q4: session-level advisory lock is fine, but the ADR must address that the lock lives on
  one connection: hold a dedicated connection for the whole run, and before publishing each
  day check that the lock is still held (if the connection was lost, stop and fail the run).
- Q6: keep 7 days of ingestion_run rows.
- Q7: the "filed under the wrong day" counter must appear in the report so figures still
  reconcile.
- Q8: postgres:17, and use the same image in docker-compose and Testcontainers.
- Q1, Q2 (2 h default), Q5, Q9: as proposed.
- Add a follow-up note for the search spec: ids change when a day is replaced, so a client
  paginating across a replacement may see a skipped or repeated result; document it there.
- Spring Boot 4 split auto-configuration into modules: double-check the right starters for
  JDBC and Flyway.

Set the spec to Approved, write the ADRs and continue with the tests; stop with the test list
grouped by AC as before.
````

### 3. Test review checkpoint

````text
OK, implement. Before/while doing it, make sure:

1. Test data isolation: every IT that touches the shared database starts from clean tables
   (e.g. truncate itinerary, day_snapshot and ingestion_run in @BeforeEach via a shared
   helper), so exact-count assertions don't depend on test order.
2. The scheduling-enabled context is really closed after IngestionSchedulingIT (no background
   runs leaking into later tests), and the "shared container ignores stop()" workaround is
   explained in a comment.
3. Keep the number of distinct Spring test contexts low (reuse property sets where possible)
   and report the total ./mvnw verify duration when done.
4. Commit the spec change (failure column) in a docs commit.
````

## Clarifications (open questions of the spec)

| # | Question | Answer |
|---|---|---|
| Q1 | Partial day: whole-snapshot rule (replace only if at least as complete, else keep stale) instead of spec 003's per-provider merge? | Yes (a merge cannot be exact: rows keep only the selected offer) |
| Q2 | A maximum staleness after which partial data replaces a stale snapshot? | Yes, 2 h |
| Q3 | Atomic replacement: delete + load in one transaction vs. partitions | Transaction; plus `ANALYZE` after each run's writes |
| Q4 | Run lock: session advisory lock vs. lease row vs. ShedLock | Advisory lock on a dedicated connection, checked before each day; a lost lock fails the run |
| Q5 | Keep the in-memory store? | Removed from the application; a fake stays in tests |
| Q6 | Run history | 7 days |
| Q7 | Itinerary filed under the wrong day | Skip, count it in the report so figures reconcile |
| Q8 | Dependencies and image | As listed (Boot 4 starters checked), `postgres:17` in compose and Testcontainers |
| Q9 | Scheduler timing | 10 s after ready, 15 min after each run ends |

Added by the author: a follow-up for the search spec (ids change on replacement, cursors may skip or repeat).

## Skills, agents and hooks involved

- Skills: `spec-feature`, `adr` (ADR-0017, 0018, 0019), `implement-spec`, `pr-description`.
- Agents: `code-reviewer`, `spec-reviewer`.
- Hooks: `format-java.sh` (Spotless after every edit), `verify-on-stop.sh`, `guard-git.sh`.

## What was done

- Spec 004 with 25 acceptance criteria; ADR-0017 (day snapshots), ADR-0018 (COPY, decided by measurement) and
  ADR-0019 (advisory lock).
- Tests first (red commit), grouped by AC, then the implementation inside-out: domain (`SnapshotPolicy`,
  `DayPublication`, `IngestionRun`), application (`DaySnapshotPublisher`, `IngestionRunService`, run query), the
  PostgreSQL adapter (COPY encoder, store, advisory lock, run repository), REST controller and scheduler, wiring.
- One shared Testcontainers `postgres:17`; the recorded-feeds Spring ITs share one context and clean the tables before
  each test; the scheduling IT runs and closes its own application.
- `docker-compose.yml` with postgres, gds-mock and flight-aggregator; heap measured at 1M and set to 768 MB.
- Measurements recorded in SOLUTION.md and ADR-0018.

## Notable corrections during the session

- **Starters.** Spring Boot 4 only auto-configures JDBC and Flyway through `spring-boot-starter-jdbc` and
  `spring-boot-starter-flyway`; checked against the 4.1.2 BOM before adding dependencies (as the author asked).
- **Test clock overridden by name.** Moving the recorded clock from a nested `@TestConfiguration` to an `@Import`ed
  one let the application's `clock` bean override it: runs used the real date and requested days the recordings
  don't have (7 IngestionIT failures, all `PARTIAL`). Fixed with a distinct bean name plus `@Primary`.
- **Closing a test context from a test.** The first scheduling IT closed its `@SpringBootTest` context to prove
  nothing runs afterwards; that breaks the test framework's cache. Rewritten to start its own application.
- **Shared container stopped by Spring.** A container returned as a `@ServiceConnection` bean is stopped when any
  context closes; the shared one now ignores `stop()` (comment in `TestPostgres`).
- **Money bounds.** The round-trip test used a final price of 1,049,999,999.99, which `Money` (9 integer digits)
  cannot represent; corrected to the real maximum.
- **Microseconds.** Run instants are truncated to microseconds (PostgreSQL's precision) so stored runs read back equal.
- **An Error swallowed.** The first run service turned an `Error` into a `FAILED` run without rethrowing; it now records
  the run, releases the lock, completes the future and rethrows.
- **Unfair benchmark.** The first batched-insert measurement ran without `reWriteBatchedInserts`; re-measured with it
  (both numbers recorded). The batched loader then moved to test sources.
- **An invented figure.** A `recordsReceived` value in the usage example was not from a real run; replaced by `…`.
- **Restart policy.** Measuring 384 MB showed the container exits on `OutOfMemoryError` and stays down; compose now
  restarts it, and the orphaned run was marked `ABORTED` by the next one (AC-15 observed live).

## Review findings and how they were handled

Both agents ran on the branch after the implementation and docs commits. Fixes are in one `fix:` commit.

### `code-reviewer` (verdict: needs changes)

| # | Finding | Severity | Outcome |
|---|---|---|---|
| 1 | Day coordinators call `isHeld` concurrently on the one lock connection (JDBC connections and Hikari's statement list are not thread-safe); a `RuntimeException` there escaped and failed the run | Blocking | **Fixed:** every use of the lease connection is serialised with a `ReentrantLock` (no virtual-thread pinning); `isHeld` treats any failure as "lost"; `IngestionService` also treats a throwing guard as a lost lock (new tests: 50 concurrent checks, failing guard) |
| 2 | A failed unlock on a live session returned the pooled connection *still holding the lock* | Blocking | **Fixed:** the lock uses its own **non-pooled** connections (`PostgresAdvisoryRunLock.connectingTo`, from `JdbcConnectionDetails`), so closing a lease always ends the session; ADR-0019 updated |
| 3 | A run interrupted at shutdown kept its interrupt flag through the bookkeeping, so the final `update` could fail and leave it `RUNNING` | Blocking | **Fixed:** the flag is cleared for statistics + final state + unlock and restored afterwards (unit test). The new IT that closes the application mid-run then exposed a **second, real bug**: the task scheduler's lifecycle stop waited for the tick, which waited for the run, so shutdown took a whole run and the executor never interrupted anything. The schedule is now stopped on `ContextClosedEvent`; the IT checks the run ends `FAILED`, stored, with no lock left |
| 4 | Flaky `IngestionApiIT`: the final state is stored just before the lock is released, so the next test's `POST` could get a 409 | Should fix | **Fixed:** `@AfterEach` also waits for no advisory lock |
| 5 | Raw exception text (SQL, hosts) stored in `failure` and served by the API | Should fix | **Fixed:** the failure names the error type only; details stay in the log under the run id |
| 6 | After a lost lock, the day being written can still commit while another instance runs (no fencing token) | Should fix | **Documented** (SOLUTION.md, ADR-0019): each day stays atomic and concurrent writers of a day are serialised by PostgreSQL; a fencing check in the write was judged not worth it here |
| 7 | Docker's 10 s stop timeout is shorter than the 30 s shutdown wait | Should fix | **Fixed:** `stop_grace_period: 40s` |
| 8 | Executor-before-DataSource shutdown order only held by creation order | Should fix | **Fixed:** the executor bean declares the `DataSource` dependency |
| 9 | A retention failure stopped the whole run | Nice to have | **Fixed:** retention is best effort (logged, retried next run; unit test) |
| 10 | Pool size not related to concurrent days | Nice to have | **Documented** in usage.md and SOLUTION.md (pool ≥ days + 2) |
| 11 | Prices with scale > 2 would be rounded by `numeric(12,2)` | Nice to have | **No change:** `Money` always has scale 2 (domain invariant) |
| 12 | HTTP vocabulary (title, properties) in `shared.domain.ConflictException` | Nice to have | **No change:** kept as generic problem metadata so the shared handler needs no feature knowledge; noted as a trade-off |
| 13 | Unauthenticated `POST` | Nice to have | Already a limitation in SOLUTION.md |
| 14 | Scheduler uses `Instant.now()`, not the injected clock | Nice to have | **Commented:** the task scheduler runs on wall-clock time on purpose |

### `spec-reviewer` (verdict: gaps found, all minor; every automatable AC implemented and tested)

| # | Finding | Outcome |
|---|---|---|
| G1 | AC-25 manual check only partly recorded | **Fixed:** SOLUTION.md records healthy services, start order, queryable rows, 202/409 and survival across `down`/`up` |
| D1 | `Location` is absolute, spec shows a relative path | **Spec amended** (absolute, built from the request) |
| D2 | Extra `failure` member in the run response | **Spec amended** |
| D3 | A complete day did not replace a snapshot listing a provider no longer configured | **Fixed:** `SnapshotPolicy` replaces whenever no provider is missing (new test); AC-2 wording clarified |
| D4 | No INFO log for `NO_DATA` | **Fixed** |
| T1 | Fixed sleeps in two tests | **Fixed:** bounded `await().during(...)` |
| T2 | No end-to-end test of a lost lock | **Added:** `IngestionRunLockIT.lockLostMidRun` kills the lock's session mid-run; the run is `FAILED`, nothing published |
| T3 | AC numbers shared by specs 003 and 004 | **Fixed:** every spec 004 test name starts with "spec 004" |
| T4 | 409 `instance` and virtual threads not asserted | **Added** (`IngestionApiIT`, `IngestionRunExecutorTest`) |
| T5 | The "without rewrite" benchmark figure is not reproducible from the test | **Documented** how it was measured |

## CI and code scanning findings on the PR

PR #6, first push (`9377d41`): every check green, nothing to fix.

| Check | Result |
|---|---|
| Build, tests & coverage (both modules, Testcontainers `postgres:17` on the runner) | pass, 2 min 0 s |
| Format (Spotless) | pass |
| Static security analysis (CodeQL) | pass, **0 open code scanning alerts** on the PR |
| Dependency review (new JDBC, Flyway, PostgreSQL and Testcontainers dependencies) | pass, no vulnerable dependencies |
