# ADR-0009: Minimal React + TypeScript + Vite frontend served by nginx

- **Status:** Accepted
- **Date:** 2026-11-04
- **Related spec:** [specs/006-dashboard](../../specs/006-dashboard/spec.md)

## Context

The statement asks for a frontend dashboard to search, filter and visualise itineraries in real time, booting with
the rest of the stack via `docker compose up --build`. The evaluation focus is the backend (aggregation, resilience,
volume), so the frontend should be small, typed and easy to review.

## Decision

We will build a **React + TypeScript** single-page app with **Vite**, in a `frontend/` directory:

- **No state-management libraries** (no Redux, Zustand, React Query…): component state and hooks are enough for a
  search form, a result list with "load more" (keyset cursor, ADR-0008) and ingestion status.
- Typed API client over `fetch` mirroring the backend DTOs (*amended by [ADR-0024](0024-contract-generated-client-and-frontend-toolchain.md): the types are generated from the OpenAPI description*).
- Production build served by **nginx** in its own container, which also proxies `/api` to `flight-aggregator`
  (same origin, no CORS configuration needed).

## Alternatives considered

1. **Server-side templates in the Spring app (Thymeleaf)** — couples UI to the backend module and gives a weaker
   interactive experience. Rejected.
2. **Angular / Next.js** — heavier frameworks (or an extra Node server for SSR) for a single-screen dashboard. Rejected.
3. **React with Redux / React Query** — extra concepts and dependencies without a real need at this size. Rejected.

## Consequences

- **Positive:** small, typed codebase; static assets with no Node runtime in production; simple compose topology.
- **Negative / trade-offs:** caching/retry logic that React Query would provide is written by hand; the frontend is
  outside the Maven build, so it needs its own lint/test steps in CI.
- **Follow-ups:** dashboard spec; add frontend build/test to CI when the module exists. *Done in spec 006 (ADR-0023,
  ADR-0024).*
