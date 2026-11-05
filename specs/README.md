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
| [002](002-gds-mock/spec.md) | GDS mock providers (Alpha JSON, Beta XML, Gamma CSV) with chaos | Approved |
