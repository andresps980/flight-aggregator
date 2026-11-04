---
name: spec-feature
description: Write a feature specification in specs/ from a requirement, following the SDD template. Use when the user describes a new feature, pastes part of the exercise statement, or asks to "spec" something before coding.
---

# Spec a feature

Turn a requirement into a reviewable spec **before any code is written**.

## Steps

1. Read `specs/README.md` and `specs/_template/spec.md`.
2. Pick the next number: list `specs/` and use the next `NNN` (zero-padded, e.g. `002`). Slug in kebab-case.
3. Create `specs/NNN-<slug>/spec.md` from the template and fill it in:
   - **Context / problem**: why this exists, in the user's words.
   - **Scope / out of scope**: be explicit about what is NOT included.
   - **Acceptance criteria**: numbered `AC-1`, `AC-2`… in Given/When/Then. Each one must be testable.
   - **API contract** (if any): endpoint, request/response examples, error cases with status codes.
   - **Domain model impact**: new entities/value objects, invariants.
   - **Non-functional**: validation, errors, performance, security, observability as relevant.
   - **Open questions**: every assumption you had to make. Never silently invent requirements.
4. Set status to `Draft`. Show the user a short summary plus the open questions and ask them to confirm.
5. When confirmed, set status to `Approved`.
6. If the spec implies a significant design decision (new dependency, persistence choice, integration style…),
   suggest running `/adr`.

## Quality bar

- Acceptance criteria describe observable behaviour, not implementation.
- Include unhappy paths (invalid input, not found, downstream failure).
- Keep it short: one page is better than three.
