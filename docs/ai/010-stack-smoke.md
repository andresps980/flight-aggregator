# 010 — Full-stack smoke test in CI (spec 007) and pending follow-ups

- **Date:** 2026-11-07
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `chore/007-stack-smoke` (PR 14)
- **Skills / agents / hooks:** spec written by hand from the template (short spec for a chore), `code-reviewer` agent,
  `pr-description` skill; hooks: Prettier after frontend edits, verify on stop, pre-commit / pre-push gates.

## Prompts

### 1. Task

````text
We continue the assessment. Read CLAUDE.md, docs/exercise.md, SOLUTION.md, docs/adr/, docs/ai/,
specs/ and docker-compose.yml before planning. Same conventions as previous PRs: English, SDD
(a short spec is enough for this chore), docs/ai/ session record, SOLUTION.md updates,
merge-commit history; after every push, wait for the CI run of the pushed commit and report it
by SHA. This is PR 14 in the repo. The delivery deadline is tomorrow, so keep scope tight.

Goal: prove in CI that the statement's "docker compose up --build boots flawlessly" holds, and
close the pending follow-ups.

1. Write specs/007-stack-smoke/spec.md (short) and wait for my approval. It should cover:
   - A script scripts/stack-smoke.sh, runnable locally and in CI (same script, parity), that:
     builds and starts the full stack with `docker compose up --build -d --wait` (with a
     timeout); waits for the first ingestion to COMPLETE via the frontend's nginx (port 3000,
     so the proxy is exercised); checks the catalog and a search return data; runs the e2e
     reconciliation IT (-Pe2e-tests) against the running stack; runs a resiliency scenario:
     force an outage of one provider via the gds-mock admin API, trigger an ingestion, assert
     PARTIAL with that provider FAILED and its breaker OPEN, assert search still returns data,
     then recover the provider; on failure prints `docker compose logs`; always tears down
     with `docker compose down -v`.
   - A CI override file (e.g. docker-compose.ci.yml) for deterministic, fast settings
     (gds-mock deterministic profile, small volume) without changing the default compose that
     reviewers run.
   - A new CI job "Full stack smoke" running the script (time budget ~10 min; Docker layer
     caching if it's simple), uploading compose logs on failure.
   - README: how to run the smoke script locally.

2. Pending follow-ups, each in its own fix: commit:
   - Providers table in the status panel breaks words mid-word ("COMPLE TED", "CLOS ED",
     "failure s"): cells wrap only at spaces, slightly smaller text, failures as a plain
     count, horizontal scroll inside the panel if it still doesn't fit. Keep the Playwright
     layout check green.
   - frontend/docker/smoke-test.sh leaves its containers behind on every run: record the
     container ids in the main script (not a subshell), remove them on exit (trap), and
     assert nothing is left at the end.
   - Record in docs/ai/009 that I dismissed the minimatch Dependabot alert myself as
     "Risk is tolerable" (dev-only, transitive, not shipped, no compatible fix yet), and
     reference it in the SOLUTION.md limitation.

3. Implement after approval (tests/checks first where it makes sense), run code-reviewer on
   the diff, record findings in docs/ai/.

4. Verify: ./mvnw verify and the frontend checks green; scripts/stack-smoke.sh passes locally
   from a clean state (docker compose down -v first) and report its duration; then
   /pr-description, push after my OK, wait for the CI run of the pushed commit (including the
   new job) and report it by SHA, then check code scanning as before.
````

### 2. Spec approval

````text
Spec approved:
Q1 yes, through nginx /gds-admin/ (it's also the path of the live demo); make sure the CI
override has DEMO_CONTROLS enabled.
Q2 yes, chore/007-stack-smoke.
Q3 run on every PR and push to main — it will be a required check, and a path-filtered
required check never reports on PRs that don't touch those paths, leaving them blocked.
Also make the up --build timeout configurable via an env var, and raise it in CI if the
first cold build needs more than 300 s.

Go ahead with the implementation and the F-1..F-3 fixes.
````

## Clarifications asked by the agent (spec *Open questions*)

| Question | Answer |
|---|---|
| Outage through nginx `/gds-admin/` rather than gds-mock's 8081? | Yes (the live demo's path); `DEMO_CONTROLS` on in the CI override |
| Branch `chore/007-stack-smoke`? | Yes |
| Job on every PR / push, not path-filtered? | Yes: a path-filtered required check never reports on other PRs and blocks them |

Added by the author at approval: the `up` timeout configurable by environment (`STACK_SMOKE_UP_TIMEOUT`), raised in CI
if a cold build needs it.

## What was done

| Commit | What |
|---|---|
| `docs:` | spec 007 (approved, open questions answered) |
| `fix:` F-2 | `frontend/docker/smoke-test.sh`: **reproduced first** (a run left 2 containers). Cause: `start` ran in `< <(start …)`, a subshell, so `containers+=()` never reached the `EXIT` trap. Now started in the main shell, labelled per run, removed on exit, and a 23rd check asserts no container with the run's label is left |
| `fix:` F-1 | Providers table: **tests first** — a Playwright check (4) that finds any word of the table split across line boxes (`Range.getClientRects()`), and the unit test expecting a plain count. Both failed on the old CSS (every header and status word, at 1440/1170/1024 px). Cause: `.panel { overflow-wrap: anywhere }` inherited by the cells. Fix: `overflow-wrap: normal` in the cells, provider ids `nowrap` (they broke at their hyphens), 0.8 rem text, failures as a right-aligned count; the existing `.table-scroll` scrolls a table that still does not fit |
| `fix:` F-3 | docs/ai/009 records the author's dismissal of Dependabot alert #1 (read back from the API: `tolerable_risk`, 2026-11-06 18:32 UTC); SOLUTION.md and docs/frontend.md reference it |
| `fix:` (found) | `GDS_MOCK_PROFILE=deterministic` with the default compose kept **10 % errors**: compose sets `GDS_CHAOS_DEFAULT_ERROR_RATE` explicitly and an environment variable beats the profile's file (checked: `/admin/providers` → `errorRate 0.1`). docs/usage.md claimed "turns it off" and suggested running the e2e IT against `docker compose up`, which the IT refuses. Docs and the compose comment fixed; no compose setting changed. The CI override sets the variable to `0` |
| `test:` | `scripts/stack-smoke.sh` + `docker-compose.ci.yml`, README / usage / CLAUDE.md |
| `chore:` | CI job *Full stack smoke*, with a CI-only compose file for the BuildKit GHA layer cache |

**Checked locally before review:** the script passed (14 checks, 49 s with images already built, after
`docker compose down -v`); `STACK_SMOKE_UP_TIMEOUT=5` fails at AC-1 with the compose log printed and saved, then tears
down; SIGINT and SIGTERM in the middle of the e2e IT tear down (exit 130 / 143, no container or volume left). A first
attempt at the SIGINT test was invalid — a background job of a non-interactive shell ignores SIGINT, so the run just
completed — and was redone from a Python parent. `docker compose config` showed that unquoted
`[type=gha,scope=…]` flow lists split into two entries; quoted.

## Code review (`code-reviewer` agent)

Verdict: *needs changes*, nothing blocking. It confirmed the jq paths against `docs/api/openapi.json`, that a non-JSON
200 (nginx SPA fallback) cannot pass any check, that the e2e IT fails rather than skips on a chaos mock, the trap and
subshell behaviour, and the compose merges. The author chose what to fix:

| # | Finding | Outcome |
|---|---|---|
| 1 | `up --wait-timeout` bounds only the health wait after start, not the image builds: a hung cold build was bounded by the job timeout only, and raising `STACK_SMOKE_UP_TIMEOUT` in CI would not have helped | **Fixed**: `docker compose build` is its own step, bounded by `STACK_SMOKE_BUILD_TIMEOUT` (600 s) through `timeout`/`gtimeout` when present; build time and health wait reported separately; spec AC-1 amended |
| 2 | A job-level timeout cancels the job, so `if: failure()` skips the log upload exactly then | **Fixed**: `timeout-minutes: 12` on the script step (the trap saves the log), upload on `failure() \|\| cancelled()` |
| 3 | A GHA cache export error fails the build (flaky required check); PR caches can evict `main`'s | **Fixed** `ignore-error=true`. Exporting only from `main`: **accepted** for now (revisit if the 10 GB repository cache evicts `main`'s entries) |
| 4 | "Other providers COMPLETED" passed with no other provider in the report | **Fixed**: exactly 2 others required |
| 5 | docs/ai/009 linked to this file before it was committed | **Fixed** (committed with the review fixes) |
| 6 | The e2e IT's Javadoc still suggested the default `docker compose up`, which it rejects | **Fixed**: points to the deterministic profile and `scripts/stack-smoke.sh` |
| 7 | A non-JSON 200 failed with a confusing message | **Fixed**: `jq -e`, message with the body |
| 8 | A second Ctrl-C / TERM during teardown interrupts `down -v` | **Accepted**: the next run's preflight `down -v` removes the leftovers |
| 9 | A skipped e2e test would count as a pass | **Fixed**: `skipped="0"` required |
| 10 | AC-2 says "scheduled" but the trigger was only printed | **Fixed**: `SCHEDULED` asserted |
| 11 | The e2e IT's own context uses the stack's Redis on 6379 | **Accepted**: the IT only ingests (no cache reads or keys); revisit if it ever searches |
| 12 | `smoke-test.sh`: a container created by a failed `docker run` has no recorded id | **Fixed**: cleanup also removes by label |
| 13 | Layout check 4 passes vacuously if the table does not render | **Fixed**: no cells is a problem |
| 14 | The unit test took the last cell, not the Failures column; "OPEN 7" ambiguous when a screen reader reads the row | **Fixed**: column found by its header; a visually hidden " failures" after the count |
| 15 | Breaker badge at 0.7 rem (~11 px) | **Fixed**: 0.75 rem |
| 16 | `STACK_SMOKE_UP_TIMEOUT: '300'` in CI equals the default; port preflight probes IPv4 only | Fixed the first (removed); the second accepted (compose then fails with "port is already allocated") |

## Verification and CI

Locally after the review fixes: `./mvnw verify` green; `npm run check` green (118 tests, 99.7 % lines / 95.2 %
branches); layout check 5/5; image smoke test 23/23 with no container left; `scripts/stack-smoke.sh` after
`docker compose down -v`: **15 checks in 33 s — build 2 s (local Docker layer cache warm), health wait 16 s**. macOS
has no `timeout` command, so the build was unbounded locally (the script says so); CI's runner has it.

*CI: to be completed after the push.*
