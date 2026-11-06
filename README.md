# Flight Aggregation & Cache Optimization System

An Online Travel Agency backend that ingests flight itineraries from three Global Distribution System (GDS)
providers — **Mock-Alpha** (JSON lists), **Mock-Beta** (nested XML) and **Mock-Gamma** (CSV streams) — and
deduplicates identical itineraries, keeping the cheapest offer plus a 5% OTA markup. It drops dead legs and invalid
connections and serves the result through a filterable, paginated search API and a dashboard.

Key constraints: 100,000 – 1,000,000 itinerary combinations per day, providers with 100 ms – 5000 ms latency and
random HTTP 500s (one failing provider must not break aggregation), a framework-free core domain, and a full stack
that boots with `docker compose up --build`.

Full statement: [docs/exercise.md](docs/exercise.md). Design write-up: [SOLUTION.md](SOLUTION.md).

> **Status:** complete stack. Implemented: the framework-free aggregation domain
> ([spec 001](specs/001-itinerary-aggregation/spec.md)), the mock providers ([spec 002](specs/002-gds-mock/spec.md)),
> resilient provider ingestion ([spec 003](specs/003-provider-ingestion/spec.md)), and PostgreSQL persistence with
> scheduled and on-demand runs and a first `docker compose` stack ([spec 004](specs/004-persistence-and-pipeline/spec.md)),
> the search API with keyset pagination, a catalog and a Redis cache ([spec 005](specs/005-search-api/spec.md)),
> and the dashboard with live ingestion status ([spec 006](specs/006-dashboard/spec.md)).

## Components


| Component | Role | Decision |
|---|---|---|
| `gds-mock` | Mock-Alpha (JSON), Mock-Beta (XML) and Mock-Gamma (CSV) provider APIs over a seeded, streamed world of up to 1M itineraries, with latency, random errors and an outage switch (**implemented**) | [ADR-0012](docs/adr/0012-single-gds-mock-application.md), [ADR-0013](docs/adr/0013-stateless-seeded-streamed-generation.md) |
| `flight-aggregator` | Scheduled + on-demand **ingestion** from the 3 providers in parallel, **deduplication** and pricing in the domain, day snapshots in PostgreSQL, **search API** with filters, keyset pagination and a catalog (**implemented**) | [ADR-0006](docs/adr/0006-scheduled-pre-ingestion.md), [ADR-0010](docs/adr/0010-domain-assumptions.md), [ADR-0021](docs/adr/0021-search-cursor.md) |
| PostgreSQL | Source of truth for itineraries and ingestion runs (**implemented**: Flyway schema, COPY bulk load, advisory run lock, search indexes) | [ADR-0007](docs/adr/0007-postgresql-and-redis.md), [ADR-0020](docs/adr/0020-search-indexes.md) |
| Redis | Search and catalog cache, keyed by a data version that every ingestion write bumps; optional at runtime (**implemented**) | [ADR-0022](docs/adr/0022-search-cache.md) |
| `frontend` | React + TypeScript dashboard served by nginx: search with URL state, keyset "load more", live ingestion status with refresh, charts, demo outage controls (**implemented**) | [ADR-0009](docs/adr/0009-react-frontend.md), [ADR-0023](docs/adr/0023-real-time-by-polling.md), [ADR-0024](docs/adr/0024-contract-generated-client-and-frontend-toolchain.md) |

```mermaid
flowchart LR
    ui[frontend<br/>React + nginx] -->|/api| agg[flight-aggregator]
    agg -->|scheduled / on-demand ingestion| alpha[Mock-Alpha JSON]
    agg --> beta[Mock-Beta XML]
    agg --> gamma[Mock-Gamma CSV]
    agg --> pg[(PostgreSQL)]
    agg --> redis[(Redis cache)]
    subgraph gds-mock
        alpha
        beta
        gamma
    end
```

Search reads only from PostgreSQL/Redis, never from the providers, and pages with keyset pagination
([ADR-0008](docs/adr/0008-keyset-pagination.md)).

## Requirements

- JDK 21
- Node 26 (only to work on the frontend outside Docker; see `frontend/.nvmrc`)
- Docker (for the compose stack and the Testcontainers-based integration tests)
- Git, and optionally the [GitHub CLI](https://cli.github.com/) (`gh`)
- [Claude Code](https://docs.claude.com/en/docs/claude-code/overview) for the AI-assisted workflow
- `jq` or `python3` recommended (used by the Claude Code hooks to parse JSON)

## Quick start

**Current** — build and test all modules:

```bash
./mvnw verify                                   # format check, unit + IT tests, architecture rules, coverage gate
./mvnw -pl flight-aggregator spring-boot:run    # start the aggregator on http://localhost:8080
./mvnw verify -Pslow-tests                      # also the slow 1,000,000-record tests
```

**Docker** — `docker compose up --build` starts **PostgreSQL**, **Redis**, **gds-mock** (port 8081),
**flight-aggregator** (port 8080) and the **dashboard** (port 3000), each with a healthcheck. The aggregator runs its
first ingestion about 10 s after it is healthy, then every 15 min.

![Dashboard: search panel, data status, "New data available" banner, price and provider charts, results with freshness badges](docs/images/dashboard.png)

**Open the dashboard at <http://localhost:3000>**: search and filter (the filters live in the URL), page with
"Load more", watch the data status panel, press *Refresh data* to run an ingestion now, and use the *Demo controls*
panel to take a mock provider down and watch the breaker open and days go stale
([docs/frontend.md](docs/frontend.md)). The same API through curl:

```bash
docker compose up --build -d --wait
curl http://localhost:8080/api/v1/ingestions/latest                              # the initial run, with its report
curl 'http://localhost:8080/api/v1/itineraries?origin=MAD&maxPrice=300&limit=5'  # cheapest first; follow nextCursor
curl http://localhost:8080/api/v1/catalog                                        # values for the filters
curl -X POST http://localhost:8080/api/v1/ingestions                             # start one now (409 if running)
```

The OpenAPI description is at `http://localhost:8080/swagger-ui.html` (committed copy:
[docs/api/openapi.json](docs/api/openapi.json)); the dashboard's TypeScript types are generated from it.

See [docs/usage.md](docs/usage.md) for the endpoints that currently exist and their configuration.

## Quality gates

Applied to every Maven module, and the equivalent to the frontend.

| Gate | Tool | Where |
|---|---|---|
| Formatting | Spotless (palantir-java-format) | `verify`, pre-commit hook, Claude hook, CI |
| Unit tests (`*Test`) | JUnit 5, Mockito, AssertJ (Surefire) | `verify`, CI |
| Integration tests (`*IT`) | `@SpringBootTest` on a random port (Failsafe) | `verify`, CI |
| Architecture & SOLID rules | ArchUnit (`ArchitectureTest` per module) | `verify`, CI |
| Coverage ≥ 80 % lines / 70 % branches | JaCoCo (merged unit + IT, per module) | `verify`, CI (summary + PR comment) |
| Frontend: format, lint, types | Prettier, ESLint (`jsx-a11y`, `react-hooks`), `tsc` strict | `npm run check`, pre-commit (Prettier), Claude hooks, CI |
| Frontend: tests ≥ 80 % lines / 70 % branches | Vitest + React Testing Library + MSW | `npm run check`, pre-push, CI |
| Frontend: contract | types generated from `docs/api/openapi.json`, regenerated and diffed | CI |
| Frontend: image | `frontend/docker/smoke-test.sh` (headers, proxy scope, demo flag, non-root) | CI |
| Vulnerable dependencies | Dependency Review, Dependabot (Maven, npm, Docker, Actions) | CI on PRs, weekly |
| Static security analysis | CodeQL (`security-and-quality`, Java and JavaScript/TypeScript) | CI on PRs, weekly |

Coverage report: `<module>/target/site/jacoco/index.html`.

## Development workflow (SDD)

1. **Spec** the feature in `specs/NNN-<slug>/spec.md` (Claude Code: `/spec-feature`).
2. Record significant **design decisions** as ADRs in `docs/adr/` (`/adr`).
3. **Implement** on a branch, tests first (`/implement-spec NNN`).
4. **Review** with the `code-reviewer` and `spec-reviewer` agents.
5. Open a **PR** with the What / Why / Changes format (`/pr-description`). CI must be green to merge.

`main` is protected: no direct commits or pushes (enforced by GitHub rulesets, git hooks and Claude Code hooks).
Everything in the repository is written in English (see [CLAUDE.md](CLAUDE.md)).

## Claude Code tooling

| Type | Name | Purpose |
|---|---|---|
| Skill | `spec-feature` | Requirement → spec with testable acceptance criteria |
| Skill | `implement-spec` | Spec → branch → tests → code → docs |
| Skill | `pr-description` | PR body in the required format + `gh pr create` |
| Skill | `adr` | Architecture Decision Record from the template |
| Agent | `code-reviewer` | SOLID / architecture / tests / security review of the branch |
| Agent | `spec-reviewer` | Acceptance-criteria traceability matrix, scope creep, spec drift |
| Agent | `test-engineer` | Writes tests and closes coverage gaps |
| Hook | `guard-git.sh` | Blocks commit/push on `main`, force-push and `--no-verify` |
| Hook | `format-java.sh` | Spotless after every Java/POM edit, scoped to the edited module |
| Hook | `format-frontend.sh` | Prettier after every edit under `frontend/` |
| Hook | `verify-on-stop.sh` | `./mvnw verify` and/or the frontend checks before Claude finishes, for whatever changed |
| Hook | `session-start.sh` | Activates `.githooks`, reports the current branch |

Project rules for Claude live in [CLAUDE.md](CLAUDE.md). The prompts and agents used to build the solution are
logged in [docs/ai/](docs/ai/README.md).

## Project structure

```
pom.xml               parent POM: shared plugin config (Spotless, JaCoCo, Failsafe), module list
mvnw                  Maven wrapper (run everything from the root)
flight-aggregator/    aggregation service (Spring Boot), base package com.andresps.flights
  src/main/java/com/andresps/flights/
    <feature>/domain           model and invariants (framework-free)
    <feature>/application      ports and use cases (framework-free)
    <feature>/infrastructure   adapters (REST, config, persistence...) and wiring
    shared/                    cross-feature building blocks
gds-mock/             mock GDS providers (Spring Boot), base package com.andresps.gdsmock
  src/main/java/com/andresps/gdsmock/
    inventory/                 seeded world generator, provider formats and feeds, expected figures
    chaos/                     latency, injected errors, outage switch
    shared/                    provider id, request errors, Problem Details handler
  Dockerfile                 multi-stage image (build context = repository root)
frontend/             dashboard (React + TypeScript + Vite), served by nginx; see docs/frontend.md
  src/api/                   generated API types + the fetch client
  src/hooks/, src/components/  state and presentation
  docker/                    nginx config, entrypoint (runtime config), image smoke test
docker-compose.yml    the whole stack (PostgreSQL, Redis, gds-mock, flight-aggregator, frontend)
.claude/              shared Claude Code config: settings + hooks, skills, agents
.githooks/            versioned git hooks (pre-commit, pre-push)
.github/              CI, CodeQL, Dependabot, PR template
docs/                 exercise, usage, architecture, ADRs, AI log, GitHub setup, OpenAPI (docs/api)
specs/                one spec per feature (SDD)
```

## Documentation

- [SOLUTION.md](SOLUTION.md) — architectural decisions, data modelling and pagination, limitations
- [Assessment statement](docs/exercise.md)
- [Architecture](docs/architecture.md)
- [Architecture Decision Records](docs/adr/README.md)
- [Specs](specs/README.md)
- [Usage](docs/usage.md)
- [Frontend](docs/frontend.md) — structure, data flow, how to run it, walkthrough for backend developers
- [API OpenAPI description](docs/api/openapi.json) (search and ingestion)
- [AI-assisted development log](docs/ai/README.md)
- [Repository bootstrap & GitHub setup](docs/github-setup.md)
