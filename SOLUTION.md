# SOLUTION

Write-up required by the [assessment statement](docs/exercise.md) (section 4). It summarises the decisions and
links to the ADRs, which hold the full reasoning.

> **Status: skeleton.** This document is completed as features are implemented. Sections marked *To be completed*
> will be filled with measured results, not plans.

## 1. Architectural decisions and AI tools used

### Architecture

| Decision | ADR |
|---|---|
| Hexagonal architecture organised by feature, framework-free domain and use cases | [ADR-0002](docs/adr/0002-hexagonal-architecture.md) |
| Spec-driven development: every change starts from a spec with Given/When/Then criteria | [ADR-0003](docs/adr/0003-spec-driven-development.md) |
| Automated quality gates (format, tests, ArchUnit, coverage, CodeQL) | [ADR-0004](docs/adr/0004-quality-gates.md) |
| Scheduled pre-ingestion + `POST /api/v1/ingestions` (202, 409 if running) + status endpoint; search never calls providers | [ADR-0006](docs/adr/0006-scheduled-pre-ingestion.md) |
| PostgreSQL (Flyway, `JdbcClient` batching, no JPA) as source of truth, Redis as search cache | [ADR-0007](docs/adr/0007-postgresql-and-redis.md) |
| Minimal React + TypeScript + Vite frontend served by nginx | [ADR-0009](docs/adr/0009-react-frontend.md) |
| Domain assumptions: invalid connections, EUR, one airline, 5% `HALF_EVEN` markup, date filter | [ADR-0010](docs/adr/0010-domain-assumptions.md) |

*To be completed:* resilience strategy per provider (timeouts, retries, partial results) and the module/component
diagram as built.

### AI tools used

The code is developed with **Claude Code** under versioned guardrails ([ADR-0005](docs/adr/0005-ai-assisted-development.md)):

- `CLAUDE.md` with project rules (architecture, testing, git workflow, language rule).
- Skills: `spec-feature`, `implement-spec`, `adr`, `pr-description`.
- Agents: `code-reviewer`, `spec-reviewer`, `test-engineer`.
- Hooks: git guard, Spotless after edits, `./mvnw verify` before finishing a turn.

Every prompt and agent run is logged in [docs/ai/](docs/ai/README.md).

## 2. Data modelling and pagination up to 1,000,000 rows

- **Pagination:** keyset pagination on `(price, id)` instead of offset. See [ADR-0008](docs/adr/0008-keyset-pagination.md)
  for the justification: constant cost per page at any depth and stable results while data changes.
- **Storage and cache:** see [ADR-0007](docs/adr/0007-postgresql-and-redis.md).

*To be completed:* domain model (itinerary, segment, offer, money), table design and indexes, batch sizes,
deduplication key, and load/latency measurements at 100k and 1M rows.

## 3. System limitations and trade-offs

Known from the accepted decisions:

- Search data is up to one ingestion interval stale (15 min by default) — [ADR-0006](docs/adr/0006-scheduled-pre-ingestion.md).
- "No concurrent ingestion runs" is guaranteed for a single instance only — [ADR-0006](docs/adr/0006-scheduled-pre-ingestion.md).
- Keyset pagination has no "jump to page N" and no exact total count — [ADR-0008](docs/adr/0008-keyset-pagination.md).
- EUR only, single-airline itineraries, fixed 45 min / 24 h connection thresholds — [ADR-0010](docs/adr/0010-domain-assumptions.md).

*To be completed:* limitations found during implementation and what would change for production.
