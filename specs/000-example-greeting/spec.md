# Spec 000: Example greeting endpoint

- **Status:** Implemented
- **Branch:** — (template bootstrap)
- **Related ADRs:** [ADR-0002](../../docs/adr/0002-hexagonal-architecture.md)

## Context

Minimal feature that exercises every layer of the architecture and every quality gate, so the template can be
verified end to end. Delete it when the first real feature is implemented.

## Scope

**In scope:** greet a person by name over HTTP with a configurable template.
**Out of scope:** localisation, persistence, authentication.

## Acceptance criteria

- **AC-1** — Given the template `Hello, %s!`, when `GET /api/v1/greetings?name=Ana`, then `200` with
  `{"message":"Hello, Ana!"}`.
- **AC-2** — Given no `name` parameter, when `GET /api/v1/greetings`, then the name defaults to `World`.
- **AC-3** — Given a name with surrounding spaces, then the spaces are trimmed.
- **AC-4 (error)** — Given a blank name or a name longer than 50 characters, then `400` with a Problem Details body.
- **AC-5** — Given `app.greeting.template` is configured, then greetings use that template.

## API contract

`GET /api/v1/greetings?name={name}` → `200 {"message": "..."}`; errors `400` (Problem Details).

## Domain model impact

`PersonName` value object (non-blank, ≤ 50 chars, trimmed); `Greeting` value object.

## Open questions

None.
