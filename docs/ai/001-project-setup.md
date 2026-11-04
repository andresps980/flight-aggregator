# 001 — Project setup

- **Date:** 2026-11-04
- **Tool:** Claude Code (Claude Opus 5.5), plan mode first, then implementation
- **Branch / PR:** `chore/project-setup`

## Prompt

````text
We are going to solve a technical assessment starting from this template. Read CLAUDE.md and docs/ first.

Language rule: everything in this repository must be in English — code, comments, tests, docs,
specs, ADRs, commit messages and PR descriptions. Add this rule to CLAUDE.md.

1. Create docs/exercise.md containing the verbatim assessment statement pasted at the end.

2. Rewrite README.md so it describes this project instead of the template:
   - Title "Flight Aggregation & Cache Optimization System" and a short summary of the
     problem, based on docs/exercise.md (summarize it, link to docs/exercise.md for the
     full statement).
   - High-level components: gds-mock (Mock-Alpha JSON, Mock-Beta XML, Mock-Gamma CSV),
     flight-aggregator (ingestion, deduplication, search API), PostgreSQL, Redis, frontend.
   - Quick start with `docker compose up --build`, clearly marked as "planned" until the
     stack exists; keep `./mvnw verify` as the current way to build and test.
   - Keep and adapt the template sections that still apply: quality gates, SDD workflow,
     Claude Code tooling, project structure (updated to the multi-module layout).
   - Link to SOLUTION.md, docs/architecture.md, docs/adr/, specs/ and docs/usage.md.
   - Don't document features as existing before they are implemented.

3. Prepare PR 1 (branch chore/project-setup), without implementing any functionality yet:
   - Convert to a Maven multi-module build at the repository root (mvnw stays at the root):
     a parent pom, a `flight-aggregator` module (the current app, base package
     com.<choose>.flights) and a `gds-mock` module (empty for now). Spotless, JaCoCo and
     ArchUnit must keep working per module. Update CI, hooks and CLAUDE.md to the new layout.
   - Rename the project coordinates (groupId/artifactId/name, spring.application.name) from
     the template values to flight-aggregator.
   - Keep the example `greeting` feature until the first functional PR.
   - Create SOLUTION.md with the 3 sections required by the statement (skeleton linking to ADRs).
   - Create docs/ai/ to record the prompts and agents used (start with this prompt).
   - Write ADRs (using /adr) for these decisions, already agreed:
     a) Scheduled pre-ingestion (configurable interval, 15 min by default) plus an endpoint
        POST /api/v1/ingestions (202 Accepted, no concurrent runs) and a GET status endpoint;
        search reads only from the database, never from the providers.
     b) PostgreSQL (Flyway, JdbcClient with batching, no JPA) as the source of truth and
        Redis as the search cache.
     c) Keyset pagination ordered by (price, id) instead of offset pagination.
     d) Minimal React + TypeScript + Vite frontend (no state-management libraries), served by nginx.
     e) Domain assumptions: dead legs / invalid connections = segments that don't chain,
        connection time < 45 min, layover > 24 h, arrival before departure, or flight already
        departed; single currency EUR; one airline per itinerary; 5% markup configurable,
        using BigDecimal with HALF_EVEN rounding; optional date filter defaulting to the
        request date.

4. Present the plan, wait for my OK, implement it, run ./mvnw verify and prepare the PR with
   /pr-description.

Assessment statement:
<verbatim statement — stored in docs/exercise.md>
````

## Clarifications (asked by the agent during planning)

| Question | Answer |
|---|---|
| Base package for `flight-aggregator` (`com.<choose>.flights`) | `com.andresps.flights` |
| Response of `POST /api/v1/ingestions` while a run is in progress | `409 Conflict` (ProblemDetail referencing the running ingestion) |

## Skills, agents and hooks involved

- Plan mode: read-only exploration, plan approved by the author before any change.
- Skill `adr`: ADR-0006 to ADR-0010.
- Skill `pr-description`: PR body (shown to the author before pushing).
- Hooks: `format-java.sh` (Spotless, now scoped per module), `verify-on-stop.sh` (`./mvnw verify`), `guard-git.sh`.

## Outcome

- Maven multi-module build (`flight-aggregator`, `gds-mock`), package `com.andresps.flights`, coordinates renamed.
- CI, hooks and CLAUDE.md adapted to the layout; language rule added to CLAUDE.md.
- `docs/exercise.md`, new `README.md`, `SOLUTION.md` skeleton, this log, ADR-0006 – ADR-0010.
- Implementation choice not in the prompt: the parent's `<name>` is `flight-aggregator-parent` so the reactor
  output doesn't show two modules named `flight-aggregator`.
