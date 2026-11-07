# AI-assisted development log

The assessment asks to document the AI tools used and the prompts or agent systems engineered to write the code
([exercise](../exercise.md), section 4). This folder is that record.

The guardrails (rules, skills, agents, hooks) are versioned in the repository and described in
[ADR-0005](../adr/0005-ai-assisted-development.md) and [CLAUDE.md](../../CLAUDE.md).

## Conventions

- One file per working session or PR: `NNN-<slug>.md`, numbered in order.
- Each entry contains: the **prompt** (verbatim), the **clarifications** asked by the agent and the answers, the
  **skills/agents/hooks** involved, and the **outcome** (PR, what was accepted or changed by the human author).
- Prompts are recorded as written by the author. Long pasted material (such as the assessment statement) is
  referenced instead of duplicated.

## Index

| # | Session | PR / branch |
|---|---|---|
| [001](001-project-setup.md) | Project setup: multi-module build, docs skeleton, ADRs 0006–0010 | `chore/project-setup` |
| [002](002-guard-git-push-match.md) | Fix false positive in the git guard hook, add hook tests to CI | `fix/guard-git-push-match` |
| [003](003-core-domain.md) | Core domain: validation, deduplication, markup (spec 001), review findings | `feat/001-itinerary-aggregation` |
| [004](004-gds-mock.md) | gds-mock: three providers, seeded world, stats, chaos (spec 002), review findings | `feat/002-gds-mock` |
| [005](005-provider-ingestion.md) | Provider ingestion: streaming parsers, resilience, per-day aggregation (spec 003), memory at 1M, review findings | `feat/003-provider-ingestion` |
| [006](006-persistence-and-pipeline.md) | Persistence and pipeline: PostgreSQL day snapshots, COPY, advisory lock, runs over REST and schedule, compose (spec 004), review findings | `feat/004-persistence-and-pipeline` |
| [007](007-search-api.md) | Search API: filters, keyset cursor, indexes measured at 1M, Redis cache-aside with data version, catalog, OpenAPI (spec 005), review findings | `feat/005-search-api` |
| [008](008-dashboard.md) | Dashboard: React + TypeScript + Vite, live ingestion status, nginx, frontend CI and hooks (spec 006), OpenAPI follow-up, review findings | `feat/006-dashboard` |
| [009](009-dependabot-triage.md) | Dependabot triage: Spotless bump merged, TypeScript 7 / ESLint 10 declined with ignore rules, `minimatch` security update not possible | `chore/dependabot-ignore-majors` |
| [010](010-stack-smoke.md) | Full-stack smoke test in CI (spec 007): `scripts/stack-smoke.sh`, CI override, CI job; providers table wrapping, smoke-test container leak, `minimatch` dismissal recorded, deterministic-profile doc fix; review findings | `chore/007-stack-smoke` |
| [011](011-final-review.md) | Final review: SOLUTION.md rewritten with an executive summary and an observability plan, architecture diagrams (overview, 16 package pages, flows) with a drift-guard test and a Mermaid render check in CI, `OFFSET` last page measured, README quick start, clean-clone check | `docs/final-review` |
