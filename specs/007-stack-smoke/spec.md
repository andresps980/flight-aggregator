# Spec 007: Full-stack smoke test — `docker compose up --build` proven in CI

- **Status:** Implemented
- **Branch:** chore/007-stack-smoke
- **Related ADRs:** [ADR-0004](../../docs/adr/0004-quality-gates.md) (quality gates),
  [ADR-0014](../../docs/adr/0014-provider-resilience.md) (breakers), [ADR-0017](../../docs/adr/0017-day-snapshot-replacement.md)
  (stale days)

## Context

The [statement](../../docs/exercise.md) requires that *"the complete stack (Backend, Frontend, Database/Cache layer)
must boot flawlessly using a single command: docker compose up --build"* and that aggregation *"must not block or fail
entirely if one provider drops out"*. Today both are checked by hand (spec 006 AC-25/AC-30, SOLUTION.md); the e2e
reconciliation IT (spec 003 AC-22) only runs when someone starts gds-mock. This chore turns the manual check into one
script, run identically on a laptop and in CI.

## Scope

**In scope**
- `scripts/stack-smoke.sh`: boots the stack, checks it end to end through the frontend's nginx, runs the e2e IT and a
  provider-outage scenario, always tears down.
- `docker-compose.ci.yml`: an override with deterministic, fast settings; `docker-compose.yml` is unchanged.
- CI job **Full stack smoke** running the script; README section on running it locally.
- Follow-up fixes, each its own `fix:` commit (not acceptance criteria of the smoke test, listed under *Follow-ups*).

**Out of scope**
- Browser end-to-end flows on the real stack (the Playwright layout check stays on a mocked API).
- Waiting for the breaker to half-open and close again after recovery (≥ 30 s more per run; covered by spec 003 ITs).
- Changes to application code or to the default compose settings.

## Acceptance criteria

The script runs compose with `-f docker-compose.yml -f docker-compose.ci.yml` under its **own project name**
(`flight-aggregator-smoke`), so `down -v` never deletes the volumes of a reviewer's own `docker compose up` stack.
All HTTP checks go through `http://localhost:${FRONTEND_PORT:-3000}` (nginx proxy) except the e2e IT, which talks to
gds-mock on 8081 as it does today.

- **AC-1 (boot)** — Given a clean Docker state, when the script runs, then `docker compose build` (bounded by
  `STACK_SMOKE_BUILD_TIMEOUT`, default 600 s) followed by `docker compose up -d --wait --wait-timeout <N>` (default
  300 s, set with `STACK_SMOKE_UP_TIMEOUT`) brings all five services to healthy, or the script fails; both durations
  are reported. *Amended after review:* `--wait-timeout` only bounds the health wait after start, not the builds, so
  the build is its own bounded step (equivalent to `up --build`).
- **AC-2 (first ingestion)** — Given the stack is healthy, when the script polls `GET /api/v1/ingestions/latest`
  through nginx (bounded wait, default 180 s), then the first scheduled run reaches `COMPLETED`; any other finished
  status, or the timeout, fails the script.
- **AC-3 (catalog and search)** — Given that run, then `GET /api/v1/catalog` lists at least one route, and
  `GET /api/v1/itineraries` both unfiltered and filtered by that route's origin and destination return at least one
  item.
- **AC-4 (reconciliation)** — Given the stack runs the deterministic gds-mock, when the script runs
  `./mvnw -pl flight-aggregator verify -Pe2e-tests` restricted to `IngestionReconciliationE2EIT`, then it passes
  (exact reconciliation with the running mock's `/admin/world/stats`).
- **AC-5 (provider outage)** — Given the stack is healthy, when the script forces an outage of `mock-beta` through
  the gds-mock admin API (via nginx `/gds-admin/`, the demo-controls path) and starts a run with
  `POST /api/v1/ingestions` (`202`), then the run finishes `PARTIAL`, `mock-beta` is `FAILED` with `circuitState`
  `OPEN`, the other providers are `COMPLETED`, and a search still returns at least one item (days kept stale).
- **AC-6 (recovery)** — Given the outage scenario ran (pass or fail), then the script removes the outage
  (`DELETE …/outage`, `204`) and `GET /gds-admin/providers` shows no provider in outage.
- **AC-7 (diagnostics)** — Given any step fails, then the script prints `docker compose logs` (and keeps a copy in a
  file CI can upload) and exits non-zero.
- **AC-8 (teardown)** — Given any outcome, including Ctrl-C, then the script runs `docker compose down -v` for its
  project (trap on exit), and prints its total duration.
- **AC-9 (CI parity)** — Given a pull request or a push to `main`, then the CI job *Full stack smoke* runs the same
  script with no extra arguments, within a 15 min job timeout (budget ~10 min), and uploads the compose log as an
  artifact when it fails.

### CI override (`docker-compose.ci.yml`)

- `DEMO_CONTROLS=true`, so nginx proxies `/gds-admin/` (AC-5 relies on it).
- gds-mock `deterministic` profile (no latency, no random errors; the outage switch still works) and a small world
  (`GDS_WORLD_VOLUME` ≈ 20 000).
- First ingestion after ~2 s and the next scheduled one far away (e.g. 6 h), so no scheduled run collides with the
  script's manual run (`409`).
- Docker layer caching in CI only if simple: BuildKit GHA cache through a CI-only addition, never required locally.
  If it is not simple, it is left out and the measured build time is recorded.

## Follow-ups (own `fix:` commits)

- **F-1 Providers table wrapping** — status panel cells wrap only at spaces (no `COMPLE TED`, `CLOS ED`,
  `failure s`), slightly smaller text, failures shown as a plain count, horizontal scroll inside the panel if it still
  does not fit. The Playwright layout check stays green and gains an assertion that no cell breaks inside a word.
- **F-2 `frontend/docker/smoke-test.sh` cleanup** — container ids are recorded in the main shell (today `start` runs
  in a process substitution, so the `trap` sees an empty list and every run leaves two containers behind); removed on
  exit; the script asserts at the end that no container of its image is left.
- **F-3 `minimatch` alert** — record in docs/ai/009 that the author dismissed the Dependabot alert as *"Risk is
  tolerable"* (dev-only, transitive, not shipped, no compatible fix yet) and reference it in the SOLUTION.md
  limitation.

## Non-functional requirements

- Bash with `set -euo pipefail`, `curl` and `jq` only (both present on GitHub runners and macOS dev machines); Maven
  wrapper and Docker for AC-4 (Testcontainers starts its own PostgreSQL, as in the regular build).
- Fails fast with a clear message if a published port (3000, 5432, 6379, 8080, 8081) is already taken.
- Each check prints `PASS`/`FAIL` with what it checked, like `frontend/docker/smoke-test.sh`.

## Open questions

- [x] Outage requests through nginx `/gds-admin/` rather than 8081 directly — **yes**, it is also the live demo's
      path; the CI override enables `DEMO_CONTROLS`.
- [x] Branch name `chore/007-stack-smoke` — **yes**.
- [x] Run on every PR and push to `main`, not path-filtered — **yes**: it will be a required check, and a
      path-filtered required check never reports on PRs that don't touch those paths, leaving them blocked.
