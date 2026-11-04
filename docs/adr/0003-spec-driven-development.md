# ADR-0003: Spec-driven development workflow

- **Status:** Accepted
- **Date:** 2026-11-03

## Context

The team works with spec-driven development (SDD). Requirements must be explicit and reviewable before code exists,
and every change must be traceable back to the requirement it fulfils. With AI-assisted coding, a precise spec is also
the main way to keep generated code on target.

## Decision

- Every functional change starts with a spec in `specs/NNN-<slug>/spec.md` (template in `specs/_template`), with
  numbered, testable acceptance criteria (`AC-n`, Given/When/Then) and explicit open questions.
- A spec moves `Draft → Approved → Implemented`. Code is written only for `Approved` specs.
- Tests reference acceptance criteria (`@DisplayName("AC-n: ...")`); the PR lists the criteria it covers.
- One spec ≈ one feature branch ≈ one PR.

## Alternatives considered

1. **User stories in issues only** — less structured acceptance criteria and harder to version with the code.
2. **Heavier tooling (e.g. GitHub Spec Kit)** — powerful, but more process than a small codebase needs; the
   template and skills can be migrated to it later if useful.

## Consequences

- **Positive:** clear scope per PR, built-in traceability (the `spec-reviewer` agent checks it), fewer invented
  requirements.
- **Negative:** some up-front writing per feature; kept small by the one-page template.
