# 011 — Final review: SOLUTION.md, architecture diagrams, clean-clone check

- **Date:** 2026-11-07
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `docs/final-review` (PR 15, the last before delivery)
- **Skills / agents / hooks:** `pr-description` skill; hooks: Spotless after Java edits, verify on stop, pre-commit /
  pre-push gates. No spec: documentation and checks only, no behaviour change.

## Prompts

### 1. Task

````text
We continue the assessment — this is the final PR (PR 15) before delivery (deadline: tomorrow,
Sunday, end of day). Read CLAUDE.md, docs/exercise.md, SOLUTION.md, README.md, docs/ (including
docs/ai/ and docs/adr/) and specs/ before planning. Same conventions as previous PRs; after
every push wait for the CI run of the pushed commit and report it by SHA.

Goal: make the repository ready for reviewers. Branch docs/final-review.

1. Record pending results: in docs/ai/010 and SOLUTION.md, the warm-cache CI run of PR 14
   (run 42394695501 / commit b97f25a), the PR 14 merge SHA and the CI run on main.

2. Review SOLUTION.md as a reviewer would, against the statement's three required sections:
   (1) architectural decisions and AI tools utilized (prompts / agent systems engineered),
   (2) data structure modelling and pagination handling up to 1,000,000 rows,
   (3) system limitations and trade-offs. Check every claim and number against the repo
   (ADRs, measured results, docs/ai/), fix inconsistencies and outdated statements, remove
   duplication, and make it readable top to bottom (a short executive summary first, then
   details, linking to ADRs/docs instead of repeating them). Optionally add the OFFSET
   last-page measurement if it can be obtained quickly from the existing slow test.
   Show me the proposed outline and the list of corrections before rewriting.

2b. Product architecture documentation (the docs so far describe the process well but the
    product less). Create docs/architecture/ with Mermaid diagrams (rendered by GitHub),
    in English, derived from the actual code on main:
    - overview.md: system/container view (frontend/nginx, flight-aggregator, gds-mock,
      PostgreSQL, Redis) and a package dependency diagram per module (features and layers:
      domain / application / infrastructure, and shared), showing allowed dependency
      directions as enforced by ArchUnit.
    - One file per package (or per feature+layer), each with a classDiagram of that package
      only: its classes, interfaces, records and enums with their key public members (not
      every field/method), and the relationships among them (implements, uses, composes).
      Do NOT draw classes from other packages; describe interactions with other packages in
      text under the diagram (e.g. "IngestionService → calls ProviderFeed port (implemented
      in infrastructure.adapter.out.provider)", "← invoked by IngestionController").
      Keep each diagram readable (~15 types max); split a package into several diagrams if
      needed. Each file starts with 2–4 sentences on the package's responsibility.
    - flows.md: sequence diagrams for (a) an ingestion run (scheduler/REST → lock →
      parallel provider fetch with retries/breaker → per-day aggregation → snapshot publish →
      data version → report) and (b) a search request (nginx → controller → use case →
      cache decorator → PostgreSQL keyset query), including the Redis-down fallback.
    - Link everything from docs/architecture.md and README, and add a short "How to read the
      architecture" entry point.
    - Drift guard: add a small test that extracts the type names from the Mermaid
      classDiagrams and asserts each one exists in the codebase, so the diagrams can't
      silently go stale after a rename.
    Show me overview.md first so I can check the level of detail before you do all packages.

3. Review README.md: accurate quick start (docker compose up --build, URLs, what to expect
   and how long the first ingestion takes), how to run tests and the smoke script, and links.
   Check docs/usage.md, docs/architecture.md and docs/frontend.md for stale content.

4. Final screenshots: I'll retake docs/images/dashboard.png and dashboard-outage.png after the
   providers-table fix; tell me when the docs are ready for them.

5. Clean-clone check: clone the repo into a temporary directory, run
   `docker compose up --build` from scratch (no cache, after docker compose down -v on any
   running stack) exactly as a reviewer would; record the time to healthy and to first
   ingestion, and fix anything that doesn't work.

6. Final consistency pass: specs index statuses (all Implemented), ADR index, docs/ai index,
   broken links (check them), no leftover TODOs.

Then /pr-description, push after my OK, wait for CI by SHA.
````

### 2. Approval of the outline, corrections and overview.md

````text
1. overview.md level of detail is right [after you've reviewed it]: go ahead with the 16
   package pages and flows.md. Yes, install @mermaid-js/mermaid-cli to validate every diagram
   locally, and add a CI step that validates the Mermaid blocks so a broken diagram can't be
   merged.
2. SOLUTION.md outline and corrections approved, including the OFFSET last-page row and
   re-measuring the 1M compose run in step 5.
3. Beta for the outage screenshot (matches the smoke and spec 006). I'll retake it with Beta.

Addition to section 4 "What would change for production": a concrete observability
subsection. Start from what exists today (Actuator health, /actuator/metrics via Micrometer
including the Redis-fallback metric, persisted ingestion reports) — inventory the actual
metrics in the code — then the plan: Prometheus registry + Grafana dashboards (ingestion
duration per provider, failed partitions, retries, breaker state, malformed per reason, stale
days; search latency p50/p95/p99, cache hit ratio, Redis fallbacks; JVM and DB pool),
structured JSON logs with trace ids shipped to Loki, OpenTelemetry tracing across nginx →
aggregator → providers, and alerting/SLOs (breaker open > N min, FAILED runs, days stale past
max age, cache-fallback rate).
````

## Clarifications raised by the agent

| Question / finding | Outcome |
|---|---|
| The prompt names run 42394695501 as the warm-cache CI run of `b97f25a`; it is the **CodeQL** run. The CI run with the smoke job is 42394695506 | Both recorded with their roles in docs/ai/010 |
| Outline of the new SOLUTION.md and 14 corrections (stale "skeleton" header and "to be completed" stubs, future tense for done work, a table size labelled "with today's indexes" that predates the search indexes, `COPY` times without the indexes, three different `verify` times, coverage 95.5 % vs 95.2 %, 22 vs 23 image checks, a Gamma outage text where smoke and spec 006 use Beta, duplication, formatting) | Approved |
| `overview.md` before the package pages | Approved, then 16 package pages and flows.md |
| Install `@mermaid-js/mermaid-cli` (download) | Approved; pinned 11.17.0 |
| Outage screenshot provider | Beta |

## What was done

| Area | What |
|---|---|
| PR 14 record | docs/ai/010: CI 42394695506 / CodeQL 42394695501 on `b97f25a` (warm cache: build 14 s), merge `3c2dc3b`, CI 42395229006 / CodeQL 42395228991 on `main` (build 70 s: a push to `main` cannot read caches written by PR refs) |
| SOLUTION.md | Rewritten: executive summary; decisions table; one paragraph per feature with what was found; SOLID once; AI tools and the agent workflow; data model, memory, read model, indexes, keyset vs `OFFSET` (now with the last page), cache, measured results; limitations grouped by theme; *What would change for production* with an observability inventory and plan |
| Architecture docs | `docs/architecture/overview.md` (system view, package dependencies per module — every arrow is an import that exists, found by scanning the sources — and the ArchUnit rules), 16 package pages (42 diagrams total with flows and the existing ones), `flows.md` (ingestion run in two sequence diagrams, search request with the Redis-down path), `packages/README.md` index; README, docs/architecture.md and CLAUDE.md link them |
| Drift guard | `ArchitectureDocsTest`: each class diagram names its package(s) with `%% package:`; every type drawn (class declarations and relationship ends) must be declared in one of them. A second test feeds it a sample with a renamed type and a diagram without a package line. Checked by hand that renaming `PageSize` in a page fails the build with the file and type named |
| Rendering check | `scripts/check-mermaid.sh` renders every Mermaid block with mermaid-cli (pinned); a step in the required *Format* CI job (rulesets require jobs by name, so a new job would not have blocked merges). Checked that a broken diagram fails it with the parse error |
| Hooks | `classify_paths` (pre-push) and the Stop hook treat `docs/architecture/` as backend, because `ArchitectureDocsTest` reads it; `classify-paths.test.sh` extended |
| `OFFSET` last page | `SearchPerformanceIT` also times the last page of the unfiltered result: **keyset 0.51 ms vs `OFFSET` 1,116.75 ms** (median of 30, page 50,001 of 1,000,006 rows); added to docs/measurements/search-1m.md as a dated addendum |
| README | Status (all seven specs), a quick start with what to expect and the measured times, URLs, *try it* with the Beta outage, build-and-test commands, documentation map |
| Stale docs | usage.md (future tense for the report, ITs start Redis too, slow-test scope, an example comment), frontend.md (table cells no longer wrap anywhere; the stack is tested by the smoke script, not by hand), architecture.md (entry point instead of "target system components", testing table with the architecture-docs test, e2e, smoke and frontend rows), two links to old SOLUTION.md anchors |
| ADR-0004 | The diagram gates added to the quality-gates table |

**Found while writing the diagrams** (from the code, not from the old docs): `DaySnapshotStore.replace` returns
nothing; `PostgresAdvisoryRunLock` is built by a static factory on a non-pooled connection; `markStale` and retention
also bump the data version; `COPY` goes through `PGCopyOutputStream`; gds-mock's `ProviderFormats.ids()` is a list.
Mermaid parse errors found by the rendering check: `{…}` inside class members (paths written as `:id`) and `;` inside
sequence messages (a statement separator).

## Clean-clone check (step 5)

`git clone` of `main` (`3c2dc3b`) into a temporary directory, no stack or volume running. The first attempt's
`docker compose build --no-cache --pull` took 23 s, which looked warm: **`--no-cache` does not clear BuildKit cache
mounts**, and both Java Dockerfiles keep `/root/.m2` in one (219 MB). That one cache record was pruned by id and the
check repeated:

| Step | Time |
|---|---|
| `docker compose build --no-cache --pull` (Maven and npm dependencies downloaded; base images already local) | 22 s |
| `docker compose up --build -d` → all five services healthy | 18 s |
| First scheduled ingestion `COMPLETED` (94,503 itineraries, 3 × 7 partitions, every breaker `CLOSED`) | 21 s after healthy (run 18.3 s) |
| **Total, build to searchable data** | **61 s** |

Every quick-start URL answered (dashboard, `config.json`, search through nginx, Swagger UI, catalog, gds-mock admin
direct and through `/gds-admin/`). Nothing needed fixing. Base image downloads are not included: they depend on the
connection (CI's cold build, which includes them, took 74 s).

**1M rows in compose** (deterministic gds-mock, `GDS_CHAOS_DEFAULT_ERROR_RATE=0`, shipped `-Xmx768m`, search indexes
present): first scheduled run **21.5 s**, 946,044 itineraries stored, a manual replacement **29.5 s**, container
peak 0.99 GiB (both `COMPLETED`). Before the search indexes (spec 004) it was 21.2 s at the same heap: the extra index
maintenance is hidden by the parallel fetch on a first load and shows on replacement. Table: 1,248 MB right after the
two loads, **623 MB after `VACUUM FULL`** (455 MB rows + 168 MB indexes; the search indexes 28 + 37 MB), 690 bytes per
row — SOLUTION.md had shown the pre-index 558 MB as "with today's indexes".

## Code review (`code-reviewer` agent)

Verdict: *needs changes*, nothing blocking. It ran `./mvnw verify` and the render check (green), compared every
package arrow of overview.md with an import scan (exact match), every member name in the class blocks with its source,
and the config values and numbers in flows.md / SOLUTION.md with the code and measurements. Findings and outcome:

| # | Finding | Outcome |
|---|---|---|
| S1 | flows.md: "no provider delivered" drawn as `NO_DATA`, untouched; `unavailable` marks a stored day stale (bumping the data version) and returns `KEPT_STALE` | **Fixed** (both branches drawn) |
| S2 | flows.md: `limit + 1` drawn on the use case → cache arrows; only the SQL adapter adds 1 | **Fixed** |
| S3 | ingestion-application.md: `findRunning()` returns `Optional<UUID>`, `deleteStartedBefore` returns `int` (members are not checked by the guard, by design) | **Fixed** |
| S4 | SOLUTION.md: "15 PRs, each merged" — #10 and #11 were declined Dependabot bumps | **Fixed**: 13 merged PRs |
| S5 | `ArchitectureDocsTest` could miss types silently (two-way and lollipop links, a generic on the left, `<<annotation>> Foo` lines, `Foo : member`, indented or `~~~` fences), and an all-unrecognised diagram passed | **Fixed**: strict parser — every line outside a class body must be a known statement or it is reported; full arrow set; each diagram must yield a type; self-test covers each case. Checked by hand that a `-.->` line in a page fails the build |
| S6 | SOLUTION.md overstated the guard ("checked against the code") | **Fixed**: "every type in the class diagrams" |
| 1 | Declarations matched in raw source, so a Javadoc sentence could keep a deleted type "declared" | **Fixed**: comments stripped first |
| 2–3 | Self-test depends on the real `Cursor`; the test also guards gds-mock pages from the aggregator module | **Fixed**: both said in Javadoc |
| 4 | No spec for this PR | **Accepted**: documentation and checks only; said in the PR description |
| 5–6 | Script: relative paths broke after the `cd`; indented fences not found; a `git ls-files` failure read as "no diagrams" | **Fixed** |
| 8 | Only mermaid-cli is pinned, its dependencies float; GitHub's renderer version differs | **Accepted**: a pinned top-level version catches syntax errors, which is the goal; a lockfile can follow if it ever flakes |
| 9 | Every PR now downloads mermaid-cli and Chromium in a required job | **Fixed**: `~/.npm` and `~/.cache/puppeteer` cached by version |
| 10 | Hook pathspec and `classify_paths` pattern | Verified correct, no change |
| 11–16 | Doc nits: one range for the slow search case, gds-mock exposes `health,info`, README and SOLUTION timings disagreed (rounded vs measured), the README gates row implied the script runs in `verify`, `FAILED` also on a lost lock or unexpected error | **Fixed**. 13 (183 vs 182 ITs): kept 182, Failsafe's own count; 183 counts report entries |
| 17 | Magic string `"none"` in `SearchPerformanceIT` | **Fixed**: `UNFILTERED` constant |

## Verification and CI

Locally after the review fixes: `./mvnw verify` green in 68 s (flight-aggregator 509 unit + 182 integration tests,
gds-mock 146 + 41; `ArchitectureDocsTest` included); `npm run check` green (118 tests, 99.7 % lines / 95.2 %
branches; no frontend change in this PR); `scripts/check-mermaid.sh`: 45 diagrams in 21 files render; hook tests
(guard-git 39, classify-paths 18) pass; link check over every tracked Markdown file: no broken path or anchor;
external links answer `200`. `SearchPerformanceIT` (`-Pslow-tests`) green in 2 min 42 s.

**CI:** the results for the pushed commit are visible in PR 15's checks. They are not recorded here by a follow-up
commit, which would trigger another run to record (the author's decision for this last PR).

Screenshots `docs/images/dashboard.png` and `dashboard-outage.png` were retaken by the author after the
providers-table fix, with a Mock-Beta outage (matching the smoke test and spec 006).
