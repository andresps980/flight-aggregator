# 008 — Dashboard: React + TypeScript + Vite, live ingestion status, nginx, frontend guardrails (spec 006)

- **Date:** 2026-11-06
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `feat/006-dashboard` (PR 8 of the repository)

## Prompts

### 1. Kick-off

````text
We continue the assessment. Read CLAUDE.md, docs/exercise.md, SOLUTION.md, docs/adr/
(especially the frontend ADR), docs/ai/, specs/ (spec 005 is the API contract) and
docs/api/openapi.json before planning. Same conventions as previous PRs: English, SDD,
docs/ai/ session record, SOLUTION.md updates, merge-commit history. This is PR 8 in the repo.
Note: I'm a backend developer with little frontend experience — favour simple, idiomatic,
well-explained code over clever abstractions, and document it so I can explain it.

Goal: the frontend dashboard required by the statement ("search, filter, and visualize these
itineraries in real-time"): minimal React + TypeScript + Vite (no state-management or heavy
UI libraries), served by nginx, plus a small backend follow-up.

0. Backend follow-up first (own commits): document the ingestion endpoints in OpenAPI with the
   correct 202/409/404 responses instead of excluding them, and update the contract test.

1. Run /spec-feature to write specs/006-dashboard/spec.md. Cover at least:
   - Search panel: origin and destination dropdowns from GET /api/v1/catalog (destinations
     filtered by the selected origin using the catalog routes), date picker limited to catalog
     dates (showing their freshness), max price, carrier; filters reflected in the URL so a
     search can be shared/reloaded.
   - Results: cheapest first; each row shows route, stops, segments (flight numbers,
     departure/arrival times), final price prominently, base price and markup, selected
     provider and the other providers that offered it, and a freshness badge
     (COMPLETE / INCOMPLETE / STALE). Decide and document how times are displayed (UTC
     clearly labelled, or local airport time if the API provides offsets).
   - Pagination: "load more" or infinite scroll using nextCursor; per the contract, a page may
     be short or empty while hasMore is true, so the UI keeps paging; no total count shown.
   - Real-time: a data/ingestion status panel polling GET /api/v1/ingestions/latest (status,
     per-provider result, circuit breaker state, last run time); a "Refresh data" button
     calling POST /api/v1/ingestions (handles 202 and 409); when a newer run completes, the
     UI offers to reload results ("New data available").
   - A simple visualization of the current results (e.g. price distribution or offers by
     provider/carrier) — only if it can be done with plain SVG/CSS or one small, justified
     library.
   - Loading, empty and error states (ProblemDetail messages shown readably); basic accessibility
     (labels, keyboard navigation).
   - Optional, behind a build/runtime flag and clearly separated: a "demo controls" panel to
     toggle gds-mock provider outages via its admin API (proxied by nginx), useful for
     demonstrating resiliency live. Propose it; I'll decide.
   - Delivery: nginx serves the static build and proxies /api to the aggregator (same origin,
     no CORS); multi-stage Dockerfile (node build → nginx); frontend service added to
     docker-compose with a healthcheck.
   Show me the spec with open questions and wait for my approval.

2. Design guidance: TypeScript types generated from docs/api/openapi.json (e.g.
   openapi-typescript) so the client can't drift from the contract; a thin API client module;
   components small and focused; state in React hooks only. Pin the Node LTS version
   (.nvmrc / engines / Dockerfile). Use /adr if a significant choice is made.

3. Tooling integration (the repo's guardrails must cover the frontend too):
   - CI: a frontend job (npm ci, lint, typecheck, unit tests with coverage threshold, build);
     add npm to Dependabot; add javascript-typescript to CodeQL.
   - Claude Code hooks: format TS/TSX/CSS (e.g. Prettier) after edits; the Stop hook and the
     pre-push hook also run the frontend checks when frontend files changed.
   - Update CLAUDE.md with the frontend commands and conventions.

4. After I approve the spec, /implement-spec 006 on feat/006-dashboard. Tests first:
   Vitest + React Testing Library with mocked HTTP (e.g. MSW or a small fetch mock): filters
   build the right query; destination list depends on origin; paging with nextCursor,
   including short/empty pages with hasMore; ProblemDetail errors displayed; status panel
   polling; refresh button with 202 and 409; "new data available" flow; URL state round-trip.
   After writing the tests (before implementing), stop and show me the list grouped by AC.

5. Run code-reviewer and spec-reviewer, fix findings, record in docs/ai/. Write
   docs/frontend.md: structure, data flow, how to run it locally (Vite dev server proxying
   to the backend) and a short walkthrough of the main components, aimed at a backend
   developer. Update README (quick start, screenshot if possible), docs/usage.md and SOLUTION.md.

6. Verify: ./mvnw verify and the frontend checks green (report durations);
   docker compose up --build brings all five services up healthy, the dashboard loads at the
   documented URL, searches return data after the first ingestion, and the refresh button
   works. Then /pr-description, push after my OK, and check CI and code scanning as before.
````

### 2. Spec approval with changes

````text
Spec approved with these answers:

Q1 yes. Q2 yes. Q3 yes. Q4 yes.
Q5 change: show "New data available" only for runs that published data (COMPLETED or
PARTIAL). A FAILED run shows a warning in the status panel instead (data may be stale); the
freshness badges already reflect stale days after a reload.
Q6 provider (it shows the aggregation result). Q7 yes. Q8 yes.
Q9 approved: runtime flag DEMO_CONTROLS, on by default in compose; label the panel clearly as
a demo control acting on gds-mock.
Q10 yes. Q11 yes. Q12 yes.
Q13 a real screenshot; if browser automation doesn't work, tell me and I'll take it manually.

Mark the spec Approved, write the ADRs, set up the tooling and write the tests first; stop
with the test list grouped by AC.
````

### 3. Test review checkpoint

````text
Test list approved. I applied the .claude/settings.json change myself (format-frontend hook
in PostToolUse, plus npm run / npm ci permissions). Commit it in a build: commit, and record
in docs/ai/ that the auto-mode classifier blocked the agent from modifying its own settings
and a human applied the change — mention it in SOLUTION.md's AI section as an example of the
guardrails working. Accepting the 7 moderate dev-only npm audit advisories as a documented
limitation is fine.

Then implement against the tests and continue with the remaining steps (reviewers, docs
including docs/frontend.md, verification with docker compose, PR description; push after my OK).
````

### 4. Layout bug found by the author

The author first sent this prompt with placeholders left unfilled and the screenshot at `/tmp/layout-bug.png`; the
agent could not read it (outside the session's allowed directories) and started from the CSS. The author then resent
it, filled in:

````text
Layout bug in the dashboard: the right-hand panels overlap the left-hand ones, so they can't
be read. Screenshot: ./docs/images/dashboard.png (read it). Browser: brave,
window.innerWidth = 1170. It happens at
these widths.

Fix the CSS layout so panels never overlap at any width:
- typical cause: grid/flex children with wide content (tables, SVG) need min-width: 0 /
  overflow handling, and fixed widths should become minmax()/fr;
- below a reasonable breakpoint, stack the side panels under the results instead of
  side by side;
- long content (segment lists, provider names) wraps or scrolls inside its own panel.
Then add a layout check so this can't regress silently: a small Playwright test (dev
dependency, Chromium only) that loads the built app with mocked API responses at 3 viewport
widths (e.g. 1440, 1024, 768) and asserts the main panels' bounding boxes don't intersect.
Run it in the frontend CI job. Tell me when it's fixed so I can re-check in the browser
before taking the screenshots.
````

### 5. Screenshots, contract regeneration, push

````text
Layout looks good in Brave, including below 960 px. New screenshots saved:
docs/images/dashboard.png (final) and docs/images/dashboard-outage.png (Beta outage: run
PARTIAL, breaker OPEN, results STALE). Add the first to the README and docs/frontend.md and
the outage one to SOLUTION.md's resiliency section; commit them.

If not done yet: the OpenAPI contract test must only compare and fail on drift in
./mvnw verify and CI; regenerating docs/api/openapi.json must be an explicit action (system
property or Maven profile), documented in docs/frontend.md / CLAUDE.md, with a check proving
that a drift fails.

Then add the layout fix to the PR description, push, open the PR and check CI and code
scanning (including the first CodeQL analysis of the TypeScript code) as before.
````

Noted by the agent: the outage screenshot shows **Mock-Gamma** down (not Beta); the caption in SOLUTION.md says
Gamma.

## Clarifications (open questions of the spec)

| # | Question | Answer |
|---|---|---|
| Q1 | Times: UTC labelled (the API has no airport offsets)? | Yes |
| Q2 | "Load more" with page size 20, auto-continue over ≤ 5 empty pages? | Yes |
| Q3 | Polling 30 s idle, 3 s while running, paused in a hidden tab? | Yes |
| Q4 | Search on submit (and at load from the URL), not per control change? | Yes |
| Q5 | Banner for any finished newer run? | **Changed**: only for runs that published data (COMPLETED / PARTIAL); FAILED warns in the status panel |
| Q6 | Price histogram + results by provider? | Provider (it shows the aggregation result) |
| Q7 | `<select>` of catalog dates instead of a native date picker? | Yes |
| Q8 | Port 3000? | Yes |
| Q9 | Demo controls behind a runtime flag, on in compose? | Approved; panel clearly labelled as acting on gds-mock |
| Q10 | Node 26 (Active LTS)? | Yes |
| Q11 | The listed npm dependencies (asked first, per the working agreement)? | Yes |
| Q12 | ADR-0023 (polling) and ADR-0024 (client and toolchain)? | Yes |
| Q13 | Screenshot via browser automation? | Real screenshot; if automation fails, the author takes it |

## Skills, agents and hooks involved

- Skills: `spec-feature`, `adr` (ADR-0023, ADR-0024; ADR-0009 amended), `implement-spec`, `dataviz` (form, mark specs,
  palette validation for the two charts: one hue, `#2a78d6` light / `#3987e5` dark, validator passed in both modes),
  `pr-description`; `chrome-browser` was loaded for the screenshot but no browser tools were available.
- Agents: `code-reviewer`, `spec-reviewer` (run in parallel while the compose stack was building).
- Hooks: `format-frontend.sh` (new, Prettier after edits), `format-java.sh`, `verify-on-stop.sh` (now one gate per
  area), `guard-git.sh`, `SessionStart`; git `pre-commit` caught an unformatted `styles.css` before a commit.

## The guardrails stopping the agent

While setting up the tooling, the agent tried to edit `.claude/settings.json` to register the new Prettier hook and
allow `npm run` / `npm ci`. **Claude Code's auto-mode classifier blocked the edit as self-modification**: an agent
changing its own hooks and permissions. The agent wrote the hook script itself (`.claude/hooks/format-frontend.sh`),
reported the exact JSON to add, and continued with the rest. **The author applied the change by hand**; the agent
committed it (`build: register the Prettier hook and allow npm run / npm ci (applied by the author)`). This is the
intended division of labour: the agent cannot widen its own permissions; a human reviews and applies such changes.

## What was done

- **Backend follow-up**: ingestion endpoints documented in OpenAPI (`202` + `Location`, `409` with
  `runningIngestionId`, `200`/`400`/`404`); springdoc widened to `/api/v1/**`; an `OpenApiCustomizer` makes response
  properties required/nullable, flattens `ProblemDetail`, writes nullable references as `anyOf`; contract IT extended
  (responses, enum values match the Java enums, required/nullable); ArchUnit rule against `@JsonInclude` on responses.
- **Spec 006** with 30 acceptance criteria; ADR-0023, ADR-0024.
- **Tooling**: frontend toolchain, CI job (types diff, Prettier, ESLint, tsc, coverage, build, image smoke test),
  CodeQL `javascript-typescript`, Dependabot npm + Docker, Prettier hook, Stop / pre-push hooks per gate
  (`classify_paths`, tested), pre-commit Prettier, CLAUDE.md.
- **Tests first** (red commit): 112 Vitest tests grouped by AC plus 19 image checks; then the implementation.
- **Implementation**: API client, four hooks, ten components, pure modules, CSS; nginx, entrypoint, Dockerfile, compose.
- **Docs**: `docs/frontend.md` (for a backend developer), README, `docs/usage.md`, `docs/architecture.md`,
  SOLUTION.md.

## Notable corrections during the session

- **Peer dependencies**: ESLint 10 is not supported by `eslint-plugin-jsx-a11y`, TypeScript 7 not by
  `typescript-eslint` / `openapi-typescript`: pinned ESLint 9 and TypeScript 5.9 instead of `--legacy-peer-deps`.
- **A contract bug found by the generated types**: `report` came out as never-null; springdoc's `$ref` + `type: null`
  is read as "both" in OpenAPI 3.1. Fixed in the backend (`anyOf`), regenerated, checked by the contract IT.
- **A misleading green**: an edit to the customizer silently did not apply (Spotless had re-wrapped the target line),
  and the IT passed with `-Dopenapi.write=true` because it rewrote the file it compared against. Found by checking the
  regenerated file, then by debugging output; the new assertion was confirmed red before the fix.
- **msw 3** renamed `onUnhandledRequest` to `onUnhandledFrame`; the old option was ignored, so unexpected requests
  only warned. Found by `tsc`.
- **Two tests adjusted** after implementation, recorded in their own commit: result rows are selected by name (the
  segments are a nested list), and the keyboard test picks the origin with `selectOptions` (user-event does not
  simulate arrow keys on a native `<select>`).
- **React lint `set-state-in-effect`** flagged five effects; the design changed to start searches from events and to
  remount the form by key instead of syncing props into state.
- **Prettier and `.editorconfig`**: Prettier inherited 4-space indentation; the frontend pins 2.
- **Healthcheck**: `localhost` resolved to `::1` inside the nginx container; the compose stack showed `frontend`
  unhealthy while serving. Fixed (`127.0.0.1`) and covered by the smoke test.

## The layout bug (prompt 4)

- **Cause** (screenshot: results column at x ≈ 325 px over a left column that needed more): grid and flex items default
  to `min-width: auto`, i.e. never narrower than their widest content. The providers table and the `<select>`s (as
  wide as their longest option, e.g. *"2026-11-09 · INCOMPLETE (missing …)"*) made the left column's content wider
  than its `minmax(18rem, 22rem)` track; jsdom has no layout, so no unit test could see it.
- **Fix**: named grid areas with `minmax(16rem, 22rem) minmax(0, 1fr)`, one column below 60rem (search, results, then
  status and demo, as asked), `min-width: 0` on grid children, full-width selects, `overflow-wrap: anywhere` in
  panels, the providers table in a horizontal scroll container, wrapping result rows, clipped chart SVGs with shortened
  labels.
- **Layout check**: `e2e/layout.spec.ts` (Playwright 1.65, Chromium headless shell only), production build via `vite
  preview`, all API calls mocked with wide content, widths 1440 / 1170 (the author's) / 1024 / 768. Asserts panels do
  not intersect, no visible element sticks out of its panel, no horizontal page scroll (naming the culprits), and that
  the status panel is under the results below the breakpoint. Runs in the CI frontend job (report uploaded on failure).
- **It found a second bug**: the charts' screen-reader tables carried `visually-hidden` themselves; `overflow` does not
  apply to table boxes, so their cells widened the page to 782 px in a 768 px window. The class moved to a wrapping
  `<div>`.
- **Is the check real?** Against the old layout it fails at every width (at 768 px with the page-scroll message; at the
  wider widths the old markup has no grid areas). Against the new layout with the results column deliberately shifted
  left it reports `area-search / area-status / area-demo overlaps area-results`; a deliberately wide table inside its
  scroll container is correctly not reported.
- Test fixes while writing it: two selectors matched twice (the hidden chart table also has a provider row; the demo
  text contains "Days"); the `<details>` is opened programmatically, so an overlapped element cannot turn the test into
  a 30 s timeout; it asserts the four panels exist, so the overlap check can never pass on an empty list.

## Contract regeneration made explicit (prompt 5)

Before: `OpenApiContractIT` compared by default, but `-Dopenapi.write=true` rewrote `docs/api/openapi.json` **and then
passed by comparing the file with itself** (the misleading green noted above). Now:

- `./mvnw verify` and CI only compare; the failure message names the regeneration command.
- `-Popenapi-write` (root POM profile) sets `openapi.write` for Failsafe, runs only this IT, writes the file and
  reports the test **aborted** (JUnit `Assumptions.abort`), never passed; with `CI` set it fails instead.
- `driftFailsTheBuild` proves the comparison rejects a hand-edited copy.
- Checked by hand, four runs: normal (6 passed); a hand-edited `openapi.json` → build fails with the regeneration
  message; `-Popenapi-write` on that edited file → exit 0, one test skipped, file back to the real contract (no diff);
  `CI=true` with the profile → build fails ("must never run in CI").

## Review findings and how they were handled

### `code-reviewer` (verdict: needs changes; nothing blocking)

| # | Finding | Action |
|---|---|---|
| 1 | Overlapping polls: a superseded poll's `catch` read the *new* controller and set "status unavailable" | **Fixed** (controller per poll); test reproduces it on the old code |
| 2 | A poll in flight during *Refresh data* could replace the new RUNNING run and reschedule at 30 s | **Fixed** (refresh aborts it); test reproduces it on the old code |
| 3 | Search dispatches after `await` relied on abort semantics | **Fixed**: explicit "still the current search?" check |
| 4 | Focus lost after every search (form remounted by a key from the filters) | **Fixed**: key = back/forward counter; test |
| 5 | `type="number"` hides non-numeric input (`""`), so the search ran without a price limit | **Fixed**: text + `inputMode="decimal"`; test |
| 6 | Tests not in StrictMode | **Fixed**: `renderApp` wraps in `StrictMode` |
| 7 | OpenAPI drift only caught in CI | **Fixed**: `check:api` in `npm run check`; `openapi.json` classified as frontend (test case) |
| 8 | Customizer's "required" rule relies on names and on Jackson writing nulls | Guarded: ArchUnit rule (no `@JsonInclude` on `*Response`/`*Document`), null-safe components |
| — | Nice to have: merge request headers; stop on `hasMore` without cursor; de-duplicate items across pages; skip identical history entries; simpler `useCatalog`; validate upstream URLs in the entrypoint; security headers on `/api`; no `eval` in the Stop hook; reinstall when the lock file changes; CI timeout | **Done** |
| — | CSRF on demo/refresh POSTs | Documented in SOLUTION.md (local demo; production: auth, `Origin` check, flag off) |
| — | Baseline is the last *polled* run (≤ 30 s old) | Documented in ADR-0023 (conservative: at most an unnecessary reload) |
| — | Move `OpenApiContractIT` out of the `search` package; path filters for the CI image build; pre-commit checks the working tree | Not changed (cosmetic / same as the existing Spotless check); noted here |

### `spec-reviewer` (verdict: spec satisfied; AC-25 / AC-30 manual)

| Gap | Action |
|---|---|
| Overlapping polls flag "unavailable" (same as code review #1) | **Fixed** |
| Max-price error not announced | **Fixed**: `role="alert"` |
| AC-11 wording ambiguous | Spec text clarified |
| Tooling ACs have no AC-named tests | `classify-paths.test.sh` now names AC-29; AC-26 – AC-28 are configuration, verified in CI |
| Small scope additions (Docker in Dependabot, X-Frame-Options, pre-commit Prettier, URL overrides) | Kept: hardening/tooling, listed in the PR |

## CI and code scanning findings on the PR

- [PR 8](https://github.com/andresps980/flight-aggregator/pull/8), first push: every check passed — Spotless (21 s),
  build, tests and coverage (2 min 19 s; flight-aggregator 96.0 % lines / 87.8 % branches), the new frontend job
  (format, lint, types, Vitest with coverage, build, Playwright layout check, image smoke test: 1 min 27 s),
  dependency review, CodeQL for `java-kotlin` (240 rules) and, **for the first time, `javascript-typescript`
  (201 rules)**.
- Code scanning: **0 alerts** in both languages (nothing to fix or dismiss).
- The pre-push hook ran both gates before the push (`./mvnw verify` and the frontend checks): 78 s.

## Incident: CI red after a docs-only push (flaky `HttpProviderFeedIT`)

**Prompts:**

````text
CI is red on PR #8: "Build, tests & coverage" failed in the step "Unit + integration tests,
architecture rules, coverage gate" — probably on the run triggered by your last docs-only push,
after you reported the checks. Investigate:

1. gh pr checks 8, then gh run view <run-id> --log-failed (and download the test-reports
   artifact if needed) to find exactly which test(s) failed and why.
2. If it is a flaky test (likely, since the last commit was docs-only), don't just re-run it:
   find the root cause (timing, ordering, shared state, slower CI runner), fix it, and prove
   the fix (e.g. run that test class repeatedly locally). Show me the diagnosis before fixing.
3. From now on, after every push, wait for the CI run of the latest commit and report its
   result, not an earlier run's.
4. Record the incident in docs/ai/008.

About the providers table wrapping mid-word: leave it for the next PR (I'll retake the
screenshots there together with the final ones). Add it as a follow-up note.
````

````text
Go ahead with fixes 1–3. Adjustments:
- The deterministic reproduction (400 ms delayed first response: fails before, passes after) is
  the key proof; for the stress check, 10 runs after the fix are enough, no need for 30 + 30.
- Also add the rule "after every push, wait for the CI run of the pushed commit and report that
  one (by SHA)" to CLAUDE.md's git workflow section, so it's a shared project rule, not only
  your personal memory.
- Accept the remaining risk on the timeout tests' own retry.
Then ./mvnw verify, push, wait for the CI run of that commit and report it by SHA.
````

**What went wrong in the process.** The agent reported PR 8's checks as green from the run of `603e906`, then pushed
a docs-only commit (`de8ac63`) and did not wait for its run. That run failed; the author found it. The rule "after
every push, wait for the run of the pushed commit and report it by SHA" is now in CLAUDE.md (git workflow) and in the
agent's memory.

**What failed.** Run 42259407544, one test: `HttpProviderFeedIT.fetchesPartition` — `partition.attempts()` was 2,
expected 1. The same code had passed one commit earlier; the commit only touched Markdown. Every other ERROR line in
the log came from tests that provoke failures on purpose.

**Diagnosis.**
- The class tests the real retry/breaker code against WireMock with scaled-down timeouts: **300 ms read timeout**, 3
  attempts. Any response slower than 300 ms is retried — an extra attempt.
- `fetchesPartition` runs **first** in the class (JUnit's deterministic order, per the CI report) and took **1.283 s**,
  against 25–45 ms for comparable warm fetches: one timed-out attempt plus a successful retry.
- The first request pays the cold start (JDK HTTP client and parser class loading, WireMock's first request, JIT) in
  a JVM that already holds the Spring contexts cached by earlier ITs, on a 2-vCPU runner; a GC pause or contention put
  it past 300 ms. Locally the cold first test took 0.33 s (1.0 s with an interpreter-only, 1-CPU JVM) but never failed.
- Not provable from the CI log: the adapter did not log retried attempts. Twelve other tests asserted exact counts
  under the same 300 ms timeout without being about timeouts — the same latent flakiness.

**Fix** (approved by the author; the timeout tests' own successful retry within 300 ms is an accepted residual risk):
1. Tests that are not about timeouts use `NOT_ABOUT_TIMEOUTS` (5 s read, 10 s attempt, same retries and breaker);
   only the timeout tests keep `SCALED`. Rule added to CLAUDE.md's testing conventions.
2. `@BeforeAll` warm-up: one throwaway fetch through the same code, then WireMock's journal is reset.
3. `HttpProviderFeed` logs every failed attempt at INFO (`Attempt 1/3 for mock-gamma 2026-11-06 failed: TIMEOUT (no
   data for PT0.3S (read timeout))`), so the next extra attempt explains itself in the CI log.

**Proof.**
- Deterministic reproduction, `slowRunnerIsNotAnExtraAttempt` (first response delayed 400 ms, more than the 300 ms
  timeout): with the old settings it fails exactly like CI (`expected: 1 but was: 2`); with the fix it passes (20/20
  in the class).
- Stress: the class 10 times in fresh JVMs with every CPU core busy (`yes` × 10): **0/10 failures**; the first test now
  takes 41–69 ms (330 ms cold before the warm-up).
- `./mvnw verify`: green in 67 s (flight-aggregator 507 unit + 182 integration tests).
- CI of the fix, by SHA: run 42263593591 (CI) and 42263593542 (CodeQL) on `8f86998` — **success**: build, tests and
  coverage 129 s, frontend 75 s, Spotless, dependency review, CodeQL `java-kotlin` and `javascript-typescript`; 0
  code scanning alerts. The run of the commit adding this line is reported in the PR conversation, not here (a log
  cannot contain the result of the commit that writes it).

## Verification

- `./mvnw verify` (both modules, unit + integration tests, ArchUnit, coverage gates, Spotless): **green in 65 s**
  (flight-aggregator 507 unit + 180 integration tests, 50 s; gds-mock 146 + 41, 14 s).
- `npm run check` (types regenerated and diffed, Prettier, ESLint, `tsc`, 118 Vitest tests with coverage): **green in
  7 s**, coverage 99.7 % lines / 95.5 % branches. `npm run build`: 243 kB JS (76 kB gzipped).
- `frontend/docker/smoke-test.sh`: **22/22** in 10 s. Hook tests: classify-paths 15/15, guard-git 39/39.
- `docker compose up --build` (AC-25, AC-30, by hand, twice — before and after the review fixes): five services
  healthy (the first time `frontend` stayed unhealthy: the `localhost` healthcheck, fixed); a rebuild from cached
  layers took 18 s. Through nginx on <http://localhost:3000>: the dashboard and `/config.json`
  (`demoControls: true`); after the first scheduled run (94,543 itineraries) searches return data; `POST
  /api/v1/ingestions` → `202`, then `409` with `runningIngestionId`; `/actuator` not proxied; restarting the
  aggregator → `502` meanwhile, `200` after (upstream re-resolved). Outage demo: Beta down through `/gds-admin`, manual
  run → `PARTIAL`, Beta `FAILED 0/7` with breaker `OPEN`, days `KEPT_STALE`, search items `STALE`; Beta recovered.
- **Not done by the agent: the UI in a real browser and the README screenshot.** No browser automation tools were
  available in the session (the Claude in Chrome tools were not exposed); the author takes the screenshot. The UI
  behaviour is covered by the jsdom tests.
