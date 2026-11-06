# Architecture Decision Records

We record significant decisions as lightweight ADRs ([Michael Nygard's format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)).
New ADR: copy [`0000-template.md`](0000-template.md) or run `/adr` in Claude Code.

| # | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-hexagonal-architecture.md) | Hexagonal architecture, organised by feature | Accepted |
| [0003](0003-spec-driven-development.md) | Spec-driven development workflow | Accepted |
| [0004](0004-quality-gates.md) | Automated quality gates | Accepted |
| [0005](0005-ai-assisted-development.md) | AI-assisted development with Claude Code | Accepted |
| [0006](0006-scheduled-pre-ingestion.md) | Scheduled pre-ingestion, decoupled from search | Accepted |
| [0007](0007-postgresql-and-redis.md) | PostgreSQL as source of truth, Redis as search cache | Accepted |
| [0008](0008-keyset-pagination.md) | Keyset pagination ordered by (price, id) | Accepted |
| [0009](0009-react-frontend.md) | Minimal React + TypeScript + Vite frontend served by nginx | Accepted |
| [0010](0010-domain-assumptions.md) | Domain assumptions for itineraries, deduplication and pricing | Accepted |
| [0011](0011-incremental-deduplication.md) | Incremental deduplication keyed by a deterministic itinerary key | Accepted |
| [0012](0012-single-gds-mock-application.md) | One gds-mock application exposing the three providers | Accepted |
| [0013](0013-stateless-seeded-streamed-generation.md) | Stateless, index-seeded, streamed mock data generation | Accepted |
| [0014](0014-provider-resilience.md) | Provider resilience with Resilience4j core, deadlines and per-provider breakers | Accepted |
| [0015](0015-atomic-partition-buffers.md) | Atomic partitions through a per-attempt buffer | Accepted |
| [0016](0016-day-partitioned-aggregation.md) | Aggregate one horizon day at a time, with summed statistics | Accepted |
| [0017](0017-day-snapshot-replacement.md) | Replace each day's snapshot atomically; keep a more complete snapshot when a provider is missing | Accepted |
| [0018](0018-bulk-load.md) | Bulk load day snapshots with PostgreSQL COPY | Accepted |
| [0019](0019-ingestion-run-lock.md) | No concurrent ingestion runs across instances, with a PostgreSQL session advisory lock | Accepted |
| [0020](0020-search-indexes.md) | Minimal search index set, chosen by EXPLAIN on 1M rows | Accepted |
| [0021](0021-search-cursor.md) | Opaque, unsigned keyset cursor with a filter fingerprint | Accepted |
| [0022](0022-search-cache.md) | Redis cache-aside keyed by a transactional data version, as a decorator of the query ports | Accepted |
