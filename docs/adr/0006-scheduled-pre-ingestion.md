# ADR-0006: Scheduled pre-ingestion, decoupled from search

- **Status:** Accepted
- **Date:** 2026-11-04
- **Related spec:** to be written (ingestion)

## Context

The three GDS providers answer in 100 ms – 5000 ms and occasionally fail with HTTP 500 (see
[exercise](../exercise.md)). The inventory is 100,000 – 1,000,000 itinerary combinations per day, and the search
API and dashboard must feel real-time. If a search had to wait for the slowest provider, its latency would be bounded
by 5 s and its availability by the weakest provider; deduplicating up to a million rows per request is not viable.

## Decision

We will separate **ingestion** from **search**:

- An ingestion run queries Mock-Alpha, Mock-Beta and Mock-Gamma in parallel, each with its own timeout and error
  handling, so a slow or failing provider never blocks or fails the whole run (partial results are kept and the
  provider outcome is recorded).
- Runs are **scheduled** at a configurable interval, **15 minutes by default**.
- Runs can also be triggered on demand with `POST /api/v1/ingestions`, which returns **`202 Accepted`** with a
  `Location` to the run. Runs **never overlap**: if one is in progress the request gets **`409 Conflict`** as an
  RFC 9457 `ProblemDetail` that references the running ingestion. The scheduler skips a tick under the same rule.
- `GET /api/v1/ingestions/{id}` returns the status of a run (state, timestamps, per-provider outcome and counts).
- **Search reads only from the database** (and its cache, ADR-0007), never from the providers.

## Alternatives considered

1. **On-demand fan-out per search** — latency tied to the slowest provider (up to 5 s), provider errors surface to
   users, and deduplication/markup would be recomputed on every query. Rejected.
2. **Provider push / streaming ingestion** — the mocks are request/response APIs by statement; it adds a broker and
   operational complexity without changing the read path. Rejected for this scope.
3. **Allow concurrent runs** — races on the same inventory snapshot and double load on the providers. Rejected.

## Consequences

- **Positive:** search latency is independent of provider latency and availability; dedup and markup are computed
  once per run; ingestion is observable and testable on its own.
- **Negative / trade-offs:** data is up to one interval stale (15 min by default); prices may change at the provider
  between runs; an in-process "no concurrent runs" guard only holds for a single instance.
- **Follow-ups:** ingestion spec (status model, timeouts, retries); multi-instance locking is out of scope and listed
  as a limitation in `SOLUTION.md`.
