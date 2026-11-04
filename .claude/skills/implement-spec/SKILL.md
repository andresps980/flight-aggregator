---
name: implement-spec
description: Implement an approved spec from specs/ on a feature branch, test-first, following the hexagonal architecture and SOLID rules. Use when the user says "implement spec NNN", "build feature NNN" or similar.
---

# Implement a spec

Input: a spec id (`NNN`) or path. The spec must be `Approved`; if it's `Draft`, stop and ask.

## Steps

1. **Read** `CLAUDE.md`, the spec, and related ADRs in `docs/adr/`.
2. **Branch** from an up-to-date main:
   `git switch main && git pull --ff-only && git switch -c feat/NNN-<slug>`
3. **Plan** briefly (in chat, not in a file): which ports, use cases, adapters, DTOs and tests are needed, and which
   acceptance criteria each test covers. Prefer the smallest design that satisfies the spec.
4. **Tests first** for each acceptance criterion:
   - Domain/application unit tests (`*Test.java`, no Spring, Mockito for ports).
   - Adapter unit tests where useful (standalone MockMvc for controllers).
   - At least one integration test (`*IT.java`) per endpoint/flow, covering happy and main unhappy paths.
   - Name or `@DisplayName` tests with the AC id, e.g. `@DisplayName("AC-2: rejects past departure dates")`.
5. **Implement** inside-out: domain → application ports/service → infrastructure adapters → config wiring.
   Follow the package rules in `CLAUDE.md`; `ArchitectureTest` will fail otherwise.
6. **Verify**: `./mvnw verify` must be green (tests, coverage gate, Spotless).
7. **Docs**: update `docs/usage.md` (how to use the new behaviour, with curl examples) and add/adjust ADRs if a
   design decision was made. Mark the spec `Implemented`.
8. **Commit** in small, meaningful Conventional Commits (e.g. `test: ...`, `feat: ...`, `docs: ...`).
9. **Review**: run the `code-reviewer` and `spec-reviewer` agents and address their findings.
10. Offer to run `/pr-description` to push and open the PR. Do not push without the user's confirmation.
