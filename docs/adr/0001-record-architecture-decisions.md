# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-11-03

## Context

Reviewers of this repository need to understand not only *what* was built but *why*, including the trade-offs that
were considered. Code and PR descriptions alone don't preserve that reasoning.

## Decision

We will record significant design decisions as short ADRs in `docs/adr/`, numbered sequentially and never rewritten
once accepted (they are superseded by new ADRs instead). A decision is "significant" if it affects structure,
dependencies, interfaces, quality attributes or the development process.

## Alternatives considered

1. **Wiki / external docs** — drift away from the code and aren't reviewed in PRs.
2. **Only PR descriptions** — reasoning gets scattered and is hard to find later.

## Consequences

- **Positive:** decisions are versioned and reviewed together with the code they affect.
- **Negative:** small overhead per decision; mitigated by the template and the `/adr` skill.
