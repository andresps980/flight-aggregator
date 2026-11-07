# Specs

Spec-driven development (see [ADR-0003](../docs/adr/0003-spec-driven-development.md)): every functional change
starts here.

- One folder per feature: `specs/NNN-<slug>/spec.md`, copied from [`_template/spec.md`](_template/spec.md)
  (or `/spec-feature` in Claude Code).
- Lifecycle: `Draft` → `Approved` → `Implemented`.
- Acceptance criteria are numbered (`AC-1`, `AC-2`…) and referenced by tests and PRs.

| # | Feature | Status |
|---|---|---|
| [001](001-itinerary-aggregation/spec.md) | Itinerary aggregation core domain | Implemented |
| [002](002-gds-mock/spec.md) | GDS mock providers (Alpha JSON, Beta XML, Gamma CSV) with chaos | Implemented |
| [003](003-provider-ingestion/spec.md) | Provider ingestion: streaming adapters, parallel resilient ingestion, report | Implemented |
| [004](004-persistence-and-pipeline/spec.md) | Persistence (PostgreSQL read model, day snapshots), scheduled and on-demand ingestion runs, docker compose | Implemented |
| [005](005-search-api/spec.md) | Search API: filters, keyset pagination, indexes, Redis cache, catalog, OpenAPI | Implemented |
| [006](006-dashboard/spec.md) | Dashboard: search panel, results with keyset paging, live ingestion status and refresh, visualisation; nginx, compose, frontend CI | Implemented |
| [007](007-stack-smoke/spec.md) | Full-stack smoke test: `docker compose up --build` booted, ingested, searched, reconciled and a provider outage survived, in CI | Implemented |
