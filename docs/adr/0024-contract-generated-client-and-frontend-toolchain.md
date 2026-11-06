# ADR-0024: Contract-generated TypeScript client and the frontend toolchain

- **Status:** Accepted
- **Date:** 2026-11-06
- **Related spec:** [specs/006-dashboard](../../specs/006-dashboard/spec.md)

## Context

ADR-0009 chose React + TypeScript + Vite without state-management or UI libraries. This ADR fixes the remaining
choices: how the client stays in line with the backend contract, how the frontend is tested, and how the repository's
quality gates (ADR-0004) extend to it. The author is a backend developer: tools should be the mainstream, documented
defaults, few in number, each with one job.

The backend contract is `docs/api/openapi.json`, published by the aggregator and kept equal to the code by
`OpenApiContractIT` (spec 005 AC-24). Spec 006's backend follow-up made it exact (every response property required,
nullable ones marked), so a generator can produce precise types.

## Decision

- **Types generated from the contract** with `openapi-typescript` into `frontend/src/api/schema.d.ts` (types only,
  no runtime code), committed and never edited by hand. `npm run gen:api` regenerates it; CI regenerates and fails
  on any difference. The chain Java → `openapi.json` → `schema.d.ts` is checked at both links, so a backend change
  that breaks the frontend fails `tsc`.
- **A thin hand-written client** (`src/api/client.ts`): one small function per endpoint over `fetch`, typed with the
  generated types, a 10 s timeout through `AbortController`, and one error type (`ApiError`) carrying the RFC 9457
  problem when the body is one. It is the only module that calls `fetch`. A generated runtime client
  (`openapi-fetch`, orval) was not needed for five calls.
- **Tests:** Vitest (Vite's test runner, same config and transforms) + React Testing Library (tests interact through
  labels and roles, like a user) + jsdom, with **MSW** mocking HTTP at the network level so the real client code runs
  in every test; fake timers for polling. Coverage with `@vitest/coverage-v8`, thresholds 80 % lines / 70 % branches
  like the backend.
- **Static checks:** `tsc --noEmit` (strict), ESLint (flat config: `@eslint/js`, `typescript-eslint`,
  `eslint-plugin-react-hooks`, `eslint-plugin-jsx-a11y`), Prettier for formatting (the Spotless of the frontend).
- **Node 26** (Active LTS) pinned in `.nvmrc`, `engines`, the Dockerfile and CI; `npm ci` from the committed lock file.
- **Runtime config, not build flags:** the nginx image writes `config.json` from environment variables at start
  (today only `DEMO_CONTROLS`), so one image serves every environment.

## Alternatives considered

1. **Hand-written DTO types** (ADR-0009's original wording, "mirroring the backend DTOs") — they drift silently
   from the contract; superseded by generation.
2. **Jest** — needs a separate Babel/ts-jest transform pipeline next to Vite's; Vitest reuses Vite's. Rejected.
3. **Mocking `fetch` with `vi.fn()`** — simpler, but tests then assert on how the client calls `fetch` rather than on
   the HTTP requests, and the client itself goes untested. MSW is one dev dependency. Rejected.
4. **Build-time flags (`VITE_*`)** for the demo controls — a different image per setting. Rejected.
5. **Biome** instead of ESLint + Prettier — one fast tool, but the accessibility and hooks rules the spec relies on are
   ESLint plugins. Rejected for now.

## Consequences

- **Positive:** the client cannot drift from the API unnoticed; tests exercise the real HTTP code; the gates mirror
  the backend's (format, lint, types, tests with coverage, build) locally and in CI.
- **Negative / trade-offs:** about twenty dev dependencies to keep updated (Dependabot covers npm); the generated
  file must be regenerated after an API change (CI says so).
- **Follow-ups:** browser end-to-end tests (Playwright) against the compose stack if the UI grows.
