---
name: adr
description: Record an Architecture Decision Record in docs/adr/ using the project template. Use when a design decision is made or the user asks to document why something was done a certain way.
---

# Architecture Decision Record

1. List `docs/adr/` and take the next number (`NNNN`, zero-padded to 4 digits).
2. Copy `docs/adr/0000-template.md` to `docs/adr/NNNN-<kebab-case-title>.md`.
3. Fill in:
   - **Status**: `Proposed` (or `Accepted` if the user already agreed).
   - **Context**: the forces at play — requirements, constraints, what the spec needs.
   - **Decision**: what we do, in active voice ("We will…").
   - **Alternatives considered**: at least two, each with why it was rejected.
   - **Consequences**: positive, negative and follow-ups.
4. If it supersedes an ADR, set the old one to `Superseded by ADR-NNNN` and link both ways.
5. Add the ADR to the index in `docs/adr/README.md`.

Keep it to one page. Write for an evaluator who wants to understand trade-offs quickly.
