# ADR-0023: Real-time dashboard by polling the latest ingestion run

- **Status:** Accepted
- **Date:** 2026-11-06
- **Related spec:** [specs/006-dashboard](../../specs/006-dashboard/spec.md)

## Context

The statement asks for a dashboard that shows itineraries "in real-time". In this system the data changes only when
an ingestion run publishes it (ADR-0006): every 15 minutes by default, or on demand through `POST
/api/v1/ingestions`; a run takes about 15–20 s at 100k itineraries. Searches are served from PostgreSQL and Redis in
milliseconds (spec 005). So "real-time" means: the user sees the state of the data (which run, how fresh, which
provider failed) as it evolves, and is told when newer data is available, without reloading the page.

The backend already exposes `GET /api/v1/ingestions/latest` (the most recently started run, with its per-provider
report and breaker state). The frontend must stay minimal (ADR-0009) and be explainable by a backend developer.

## Decision

We will **poll** `GET /api/v1/ingestions/latest` from the browser:

- every **30 s** while idle, every **3 s** while the latest run is `RUNNING` (also right after the user starts one);
- **paused** while the browser tab is hidden (`visibilitychange`), with an immediate poll when it becomes visible;
- a failed poll is shown as "status unavailable" and polling continues.

The results list is **never replaced automatically**. When a poll shows a run that published data (`COMPLETED` or
`PARTIAL`) and is newer than the run the current results were loaded under, the dashboard shows "New data available"
with a reload action; a `FAILED` run shows a warning in the status panel instead.

## Alternatives considered

1. **Server-Sent Events** (`/api/v1/ingestions/stream`) — pushes run changes as they happen. It needs a new endpoint,
   long-lived connections held by the servlet container (and by nginx, with buffering disabled and timeouts tuned),
   reconnection handling and tests for both. For events that happen every 15 minutes, a 30 s poll is at most 30 s
   late and costs one indexed single-row query per client per 30 s. Rejected for this scope; the natural upgrade if
   runs became frequent.
2. **WebSocket** — bidirectional, which the dashboard does not need, plus a broker or session handling in Spring.
   Rejected.
3. **Replacing the results automatically after each run** — the list would jump under a user who is reading or paging
   (keyset cursors continue across a refresh but may skip or repeat items, spec 005). Rejected in favour of an
   explicit reload.
4. **Polling the search itself** — re-runs the user's query every N seconds for no benefit: the data version only
   changes with a run. Rejected.

## Consequences

- **Positive:** no backend change; works through any proxy; trivially testable with fake timers; the load is bounded
  (≤ 1 request / 3 s / visible tab during a run, 1 / 30 s otherwise, served by a primary-key lookup).
- **Negative / trade-offs:** up to 30 s of delay before the dashboard notices a scheduled run started (3 s once it
  knows one is running); every open tab polls independently.
- **Follow-ups:** SSE if runs become frequent or many dashboards are open; an `ETag` on `/latest` would make idle
  polls cheaper still.
