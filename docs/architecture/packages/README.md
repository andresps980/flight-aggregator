# Package pages

One page per feature and layer, each with a class diagram of that package only (its key types and public members,
not every field) and, under it, the interactions with other packages in text. Read [overview.md](../overview.md)
first for the layers and the allowed dependencies, and [flows.md](../flows.md) for the runtime sequences.

Every diagram names its package(s) with a `%% package:` line; `ArchitectureDocsTest` checks that each type drawn
exists there, so a rename or move that is not reflected here fails the build. `scripts/check-mermaid.sh` (also in CI)
checks that every diagram renders.

## flight-aggregator (`com.andresps.flights`)

| Feature | Layer | Page |
|---|---|---|
| aggregation (spec 001) | domain: value objects | [aggregation-domain-model](flight-aggregator/aggregation-domain-model.md) |
| | domain: validation rules | [aggregation-domain-validation](flight-aggregator/aggregation-domain-validation.md) |
| | domain: markup and deduplication | [aggregation-domain-pricing-and-deduplication](flight-aggregator/aggregation-domain-pricing-and-deduplication.md) |
| ingestion (specs 003, 004) | domain | [ingestion-domain](flight-aggregator/ingestion-domain.md) |
| | application: ports and use cases — **start here** | [ingestion-application](flight-aggregator/ingestion-application.md) |
| | infrastructure: scheduler and REST | [ingestion-infrastructure-in](flight-aggregator/ingestion-infrastructure-in.md) |
| | infrastructure: provider feeds, parsers, resilience, FX rates (spec 008) | [ingestion-infrastructure-out-provider](flight-aggregator/ingestion-infrastructure-out-provider.md) |
| | infrastructure: PostgreSQL, report JSON, wiring | [ingestion-infrastructure-out-persistence](flight-aggregator/ingestion-infrastructure-out-persistence.md) |
| search (spec 005) | domain | [search-domain](flight-aggregator/search-domain.md) |
| | application | [search-application](flight-aggregator/search-application.md) |
| | infrastructure: REST, SQL, Redis cache, wiring | [search-infrastructure](flight-aggregator/search-infrastructure.md) |
| shared | domain exceptions, error handler, clock, OpenAPI | [shared](flight-aggregator/shared.md) |

## gds-mock (`com.andresps.gdsmock`)

| Feature | Layer | Page |
|---|---|---|
| inventory (spec 002) | domain: generator, model, statistics | [inventory-domain](gds-mock/inventory-domain.md) |
| | infrastructure: feeds, formats, stats, wiring | [inventory-infrastructure](gds-mock/inventory-infrastructure.md) |
| chaos (spec 002) | domain + infrastructure | [chaos](gds-mock/chaos.md) |
| shared | provider id, request errors, error handler | [shared](gds-mock/shared.md) |
