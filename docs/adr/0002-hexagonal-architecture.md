# ADR-0002: Hexagonal architecture, organised by feature

- **Status:** Accepted
- **Date:** 2026-11-03

## Context

The service will integrate with external systems (e.g. LLM/voice providers, travel inventory APIs) whose APIs and
vendors may change. Business rules must be testable quickly and in isolation, and the design must visibly follow
SOLID principles.

## Decision

We will use ports & adapters, packaged **by feature** (`<feature>/domain|application|infrastructure`):

- `domain`: entities, value objects, invariants — plain Java.
- `application`: inbound ports (use case interfaces), outbound ports, and use case implementations — plain Java,
  **no Spring annotations**. Use cases are instantiated as `@Bean`s in `infrastructure/config`.
- `infrastructure`: inbound adapters (REST), outbound adapters (clients, persistence, config) and wiring.

Rules are enforced by ArchUnit in `ArchitectureTest`, so violations fail the build.

## Alternatives considered

1. **Classic layered (controller/service/repository) by layer** — familiar, but tends to couple business logic to
   Spring and to persistence models, and spreads a feature across the whole tree.
2. **Hexagonal with `@Service` on use cases** — less wiring code, but couples the application layer to Spring.
   Acceptable trade-off if wiring becomes noisy; it would require relaxing one ArchUnit rule via a new ADR.

## Consequences

- **Positive:** vendors can be swapped by writing a new adapter (OCP/DIP); use cases are unit-tested without a Spring
  context; features stay cohesive.
- **Negative:** more types (ports, DTOs, config classes) than a layered CRUD app; explicit bean wiring.
- **Follow-ups:** if a cross-cutting concern like transactions is needed in a use case, decide between a decorator in
  infrastructure and relaxing the framework-free rule.
