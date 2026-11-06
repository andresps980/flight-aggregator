# Spec 006: Dashboard — search, filter and visualise itineraries, live ingestion status

- **Status:** Implemented
- **Branch:** feat/006-dashboard
- **Related ADRs:** [ADR-0006](../../docs/adr/0006-scheduled-pre-ingestion.md),
  [ADR-0008](../../docs/adr/0008-keyset-pagination.md), [ADR-0009](../../docs/adr/0009-react-frontend.md),
  [ADR-0017](../../docs/adr/0017-day-snapshot-replacement.md), [ADR-0021](../../docs/adr/0021-search-cursor.md);
  [ADR-0023](../../docs/adr/0023-real-time-by-polling.md) (real-time by polling),
  [ADR-0024](../../docs/adr/0024-contract-generated-client-and-frontend-toolchain.md) (client and toolchain)

## Context

From the [statement](../../docs/exercise.md): *"Build a frontend dashboard to search, filter, and visualize these
itineraries in real-time"*, and *"The complete stack (Backend, Frontend, Database/Cache layer) must boot flawlessly
using a single command: docker compose up --build"*.

ADR-0009 fixed the shape: a minimal React + TypeScript + Vite single-page app, no state-management or heavy UI
libraries, built to static files served by nginx, which also proxies `/api` to the aggregator (same origin, no CORS).
The backend contract is spec 005 (search, catalog) and spec 004 (ingestion runs), described in
[docs/api/openapi.json](../../docs/api/openapi.json). The author is a backend developer: the code must be simple,
idiomatic and documented well enough to explain it.

**Backend follow-up (done first, own commit):** spec 005 asked this PR to document the ingestion endpoints in OpenAPI.
They are now described (`202` + `Location`, `409` with `runningIngestionId`, `200`/`400`/`404`), springdoc covers
`/api/v1/**`, and every response property is `required` (nullable where it can be `null`), so types generated from
the file are exact. `OpenApiContractIT` checks it.

## Definitions

- **Filters** — `origin`, `destination`, `date`, `maxPrice`, `carrier`: the spec 005 query parameters, same names.
- **Search** — the first page for the current filters plus every page loaded after it with "Load more".
- **Latest run** — `GET /api/v1/ingestions/latest`: the most recently *started* run, possibly `RUNNING`.
- **Finished** — a run whose status is not `RUNNING`.
- **Baseline** — the latest run as seen when the current search was started (its id and whether it had finished);
  `none` if no run existed.

## Scope

**In scope**
- `frontend/`: React + TypeScript + Vite app; search panel, results list, pagination, status panel with refresh,
  "new data available" banner, one small visualisation; loading / empty / error states; basic accessibility.
- TypeScript types generated from `docs/api/openapi.json`; a thin API client over `fetch`.
- nginx config and multi-stage Dockerfile (Node build → nginx); `frontend` service in docker-compose.
- Tooling: CI job, Dependabot (npm), CodeQL (`javascript-typescript`), Claude Code hooks, pre-push hook, CLAUDE.md.
- Docs: `docs/frontend.md` (for a backend developer), README, `docs/usage.md`, SOLUTION.md, `docs/ai/008`.
- Demo controls to toggle gds-mock provider outages, behind the runtime flag `DEMO_CONTROLS` (Q9).

**Out of scope**
- New search features in the backend (sort orders, total counts, time zones — see Q1), authentication.
- Server-side rendering, routing between pages (one screen), i18n (English only, fixed `en-GB` formatting).
- Browser end-to-end flows (Playwright/Cypress); the compose stack is checked by hand (AC-30). *Added after a layout
  bug found by hand: a Playwright layout check in Chromium with a mocked API (panels never overlap or overflow).*
- Push channels (WebSocket / SSE) — polling is enough at a 15 min ingestion cadence (ADR-0023).
- Mobile-specific layout beyond a single responsive column.

## Acceptance criteria

**Search panel**
- **AC-1 (catalog)** — Given the dashboard loads, then it requests `GET /api/v1/catalog` once and fills the origin,
  destination, carrier and date controls from it; each control has an "Any" option (no filter). While it loads the
  controls are disabled; if it fails, the problem is shown (AC-20) and a free search (no filters) still works.
- **AC-2 (dependent destinations)** — Given an origin is selected, then the destination list contains only that
  origin's `routes[].destinations`; given "Any origin", every catalog destination. When the origin changes and the
  selected destination is not served from the new origin, the destination resets to "Any".
- **AC-3 (dates)** — The date control lists only the catalog `dates`, each labelled with its freshness
  (`2026-11-08 · COMPLETE`, `… · INCOMPLETE (missing mock-beta)`, `… · STALE`), plus "Any date" (default: every
  future departure, spec 005 AC-3).
- **AC-4 (max price, carrier)** — Max price is a number input in EUR; a value that is negative, has more than 2
  decimals or is ≥ 1,000,000,000 is flagged next to the field and the search is not sent (the server stays
  authoritative: its `400` is still shown if it disagrees). Carrier is a list of the catalog `carriers`.
- **AC-5 (query)** — When the user submits the form, then exactly one `GET /api/v1/itineraries` is sent with only
  the filters that are set (no empty parameters), `limit` = page size (Q2) and no cursor; codes as uppercase.
- **AC-6 (URL state)** — On submit the filters are written to the page URL as query parameters with the API's names
  (e.g. `/?origin=MAD&destination=JFK&maxPrice=650`; no cursor, no empty values) as a new history entry. Given the
  dashboard is opened (or reloaded) with such a URL, then the controls show those values and that search runs
  immediately; given no parameters, the unfiltered search runs (cheapest itineraries overall). Back/forward restore
  the previous filters and rerun their search. A URL value that is not in the catalog is still sent as typed (the
  server validates it).

**Results**
- **AC-7 (order and row)** — Results are shown in the order returned (cheapest first). Each row shows: route
  (`MAD → JFK`), carrier, number of stops (`Direct`, `1 stop`, `2 stops`), departure and arrival, total duration,
  every segment (flight number, `origin → destination`, departure and arrival time), the **final price**
  prominently, the base price and the markup (`final − base`, computed in cents), the selected provider, the other
  providers that offered it, and the freshness badge.
- **AC-8 (times)** — Times are shown in **UTC, labelled "UTC"** (the API sends UTC instants and no airport offsets,
  Q1), formatted `08 Nov 10:15`; an arrival on a later UTC day than its departure shows `+1` (`+2`…).
- **AC-9 (freshness badge)** — `COMPLETE`, `INCOMPLETE` and `STALE` are visually distinct and spelled out (not colour
  alone); `INCOMPLETE` names the missing providers and `STALE` shows since when, in an accessible description.
- **AC-10 (empty)** — Given a search with no results (`items: []`, `hasMore: false`), then a clear "No itineraries
  match these filters" message is shown, not an empty table.
- **AC-11 (loading)** — While a search is in flight its button shows a busy state and a second submit is ignored;
  when a newer search replaces one still in flight (back/forward, "Reload results"), the older one's results are
  never displayed.

**Pagination** ([ADR-0008](../../docs/adr/0008-keyset-pagination.md), spec 005 *Paging rule for clients*)
- **AC-12 (load more)** — Given `hasMore: true`, then a "Load more" button is shown; activating it requests the same
  filters with `cursor = nextCursor` and appends the items. Given `hasMore: false`, the button is replaced by an
  "End of results" note. No total count and no page numbers are shown; the panel says how many are loaded.
- **AC-13 (short and empty pages)** — Given a page that is shorter than `limit` or empty while `hasMore` is true,
  then the UI does not treat it as the end: after a "Load more" (or the first page) that adds no item, it fetches the
  next page automatically, up to 5 consecutive empty pages, then shows the "Load more" button again.
- **AC-14 (stale cursor)** — Given a page request fails with `400` (e.g. the cursor no longer matches), then the
  loaded items stay, the problem is shown, and a "Restart search" action reruns the first page.

**Real-time: ingestion status** ([ADR-0006](../../docs/adr/0006-scheduled-pre-ingestion.md))
- **AC-15 (status panel)** — The panel shows the latest run: status, trigger, start time, finish time and duration
  (or "running for …"), stored itineraries; per provider: status, partitions succeeded / requested, circuit breaker
  state (`CLOSED` / `OPEN` / `HALF_OPEN`, an `OPEN` breaker is highlighted) and failure count; per day (collapsed by
  default): outcome and missing providers. Given `404` (no run yet), it says so and that the first scheduled run
  starts shortly after startup.
- **AC-16 (polling)** — The panel polls `GET /api/v1/ingestions/latest` every 30 s, and every 3 s while the latest run
  is `RUNNING` (Q3); polling pauses while the browser tab is hidden and polls at once when it becomes visible again.
  A failed poll shows a non-blocking "status unavailable" note and polling continues.
- **AC-17 (refresh data)** — A "Refresh data" button sends `POST /api/v1/ingestions`. On `202` it shows that run as
  `RUNNING` at once and switches to fast polling. On `409` it says a run is already in progress (with its id when the
  problem has `runningIngestionId`) and also switches to fast polling. Other errors are shown (AC-20). The button is
  disabled while a request is in flight or the latest run is `RUNNING`.
- **AC-18 (new data available)** — Given the current search's baseline, when a poll returns a latest run that
  **published data** (`COMPLETED` or `PARTIAL`) and whose id differs from the baseline (or the baseline run was still
  `RUNNING`), then a "New data available" banner offers "Reload results"; a `FAILED` or `ABORTED` run never shows it
  (AC-15 warns instead; freshness badges show stale days after the next reload). Results are **not** replaced
  automatically (the user may be paging). Reload refetches the catalog and reruns the current filters from the first
  page, which sets a new baseline and hides the banner.

**Visualisation**
- **AC-19** — Next to the results, a price distribution of the **loaded** results (histogram of `finalPrice`, about
  10 buckets, labelled in EUR) and a count of loaded results by selected provider, drawn with plain SVG/CSS (no chart
  library). Both state "of N loaded results" (there is no total) and update as pages are appended; each has a
  text alternative (a visually hidden table or `aria-label` with the figures). Hidden when nothing is loaded.

**Errors and accessibility**
- **AC-20 (errors)** — Given a response with an RFC 9457 body (`application/problem+json`), then its `title` and
  `detail` are shown readably in an alert (e.g. *Invalid request — maxPrice must have at most 2 decimals*); given a
  non-problem error (network failure, `502` from nginx while the aggregator restarts, invalid JSON), then a generic
  message with the HTTP status is shown. Errors never leave a spinner running.
- **AC-21 (accessibility)** — Every control has a visible `<label>`; everything is reachable and operable with the
  keyboard in a logical order with a visible focus; the form submits with Enter; result count changes, the "new data"
  banner, refresh outcomes and errors are announced through `aria-live` regions; the freshness badge and breaker
  state are not conveyed by colour alone; text contrast meets WCAG AA.

**Demo controls** (Q9)
- **AC-22** — Given the runtime flag `DEMO_CONTROLS` is on, then a separate panel clearly labelled as a demo control
  acting on the mock providers ("Demo controls — act on gds-mock, not on real providers")
  lists the providers from gds-mock's `GET /admin/providers` (proxied by nginx under `/gds-admin/`) with their outage
  state and a toggle each (`POST` / `DELETE /admin/providers/{id}/outage`), and explains how to observe the effect
  (refresh data → provider `FAILED`, breaker `OPEN`, days `KEPT_STALE`, `STALE` badges). Given the flag is off, the
  panel is not rendered and nginx does not proxy `/gds-admin/` (`404`).

**Delivery**
- **AC-23 (nginx)** — nginx serves the static build, falls back to `index.html` for unknown paths (URL state),
  proxies `/api/` to `flight-aggregator:8080` (and nothing else of the aggregator: no `/actuator`, no Swagger UI),
  sets long-lived cache headers on hashed assets and `no-cache` on `index.html`, gzips text, and sends basic security
  headers (`Content-Security-Policy: default-src 'self'` …, `X-Content-Type-Options: nosniff`, `Referrer-Policy`).
  It keeps working when the aggregator container restarts (upstream re-resolved by Docker DNS, `502` meanwhile).
- **AC-24 (image)** — `frontend/Dockerfile` is multi-stage: `node:<LTS>-alpine` runs `npm ci` and `npm run build`;
  the final image is `nginx:<stable>-alpine` with only the static files and the config, running as a non-root user.
- **AC-25 (compose)** — `docker compose up --build` starts a `frontend` service on `http://localhost:3000`
  (`FRONTEND_PORT` to override) with a healthcheck, after `flight-aggregator` is healthy; five services healthy.
- **AC-26 (Node pinned)** — The Node LTS major is pinned in `frontend/.nvmrc`, `package.json` `engines`, the
  Dockerfile and CI; `package-lock.json` is committed and installs use `npm ci`.

**Contract and tooling**
- **AC-27 (generated types)** — The API types used by the client are generated from `docs/api/openapi.json` with
  `openapi-typescript` (`npm run gen:api`), committed, and never hand-edited; CI regenerates them and fails if they
  differ. The client module is the only code that calls `fetch` for `/api`.
- **AC-28 (CI and scanning)** — A `frontend` CI job runs `npm ci`, lint (ESLint), format check (Prettier), typecheck
  (`tsc`), unit tests with coverage thresholds (80 % lines / 70 % branches, as the backend) and the production build;
  Dependabot covers npm in `frontend/`; CodeQL also analyses `javascript-typescript`.
- **AC-29 (local guardrails)** — Claude Code formats `.ts`/`.tsx`/`.css` (Prettier) after each edit; the Stop hook and
  the pre-push hook run the frontend checks (lint, typecheck, tests) when frontend files changed, and `./mvnw
  verify` only when backend files changed; CLAUDE.md lists the frontend commands and conventions.
- **AC-30 (stack, by hand)** — With `docker compose up --build`: five services healthy; the dashboard loads at
  `http://localhost:3000`; after the first ingestion a search returns data; "Refresh data" starts a run (and a second
  click gets the `409` message); the "new data available" banner appears when it finishes.

## API contract

No backend change beyond the OpenAPI follow-up above. Consumed endpoints (all same origin, through nginx):

| Call | Used for | Responses handled |
|---|---|---|
| `GET /api/v1/catalog` | dropdowns, dates with freshness | `200`; any error → AC-20 |
| `GET /api/v1/itineraries?…&limit&cursor` | results, pagination | `200`; `400` problem → AC-14/AC-20 |
| `GET /api/v1/ingestions/latest` | status panel, new-data detection | `200`; `404` = no run yet |
| `POST /api/v1/ingestions` | refresh data | `202` (run, `Location`); `409` problem with `runningIngestionId` |
| `GET /gds-admin/providers`, `POST`/`DELETE /gds-admin/providers/{id}/outage` | demo controls (AC-22) | `200`, `204`; `404` unknown provider |

## Design (for review; details in ADR-0023 / ADR-0024)

- **Structure** (`frontend/src/`): `api/` (generated `schema.d.ts`, `client.ts` with one function per endpoint and a
  `ProblemError`), `hooks/` (`useCatalog`, `useSearch` — first page, load more, auto-continue, stale-result guard —,
  `useIngestionStatus` — polling with visibility pause —, `useUrlFilters`), `components/` (one per panel / row /
  badge / chart), `format.ts` (UTC times, durations, EUR, stops). State lives in hooks (`useState`, `useEffect`,
  `useReducer` where a state machine is clearer); no global store, no context beyond what a prop chain can't do.
- **Data flow**: URL → filters → `useSearch` → client → nginx → aggregator. `useIngestionStatus` is independent;
  `App` compares its latest run with the search's baseline to show the banner (AC-18).
- **Runtime config**: the nginx container's entrypoint writes `/config.json` (`{"demoControls": true|false}`) from
  `DEMO_CONTROLS` and enables the `/gds-admin/` proxy only when it is on; the app reads `config.json` once at start
  (missing or invalid = off).
- **Tests**: Vitest + React Testing Library + jsdom, HTTP mocked at the network level with MSW (the real client code
  runs); fake timers for polling. Each AC maps to at least one test named after it.

## Non-functional requirements

- **Simplicity**: no state-management, routing, UI-kit, date or chart library; runtime dependencies are `react` and
  `react-dom` only. Every component and hook has a short comment saying what it owns.
- **Performance**: one catalog request per load/reload; no request per keystroke (explicit submit); polling at most
  every 3 s and only while visible; production bundle reported in `docs/frontend.md`.
- **Security**: same origin only (no CORS, no third-party requests), CSP `default-src 'self'`; React escapes all
  rendered text; the demo proxy (if any) is limited to `/admin/providers` paths.
- **Robustness**: every request has a timeout (10 s) via `AbortController`; unmounted or superseded requests are
  aborted.

## Open questions

All resolved (2026-11-06):

- [x] Q1 Times — UTC, clearly labelled (AC-8); local airport times listed as a limitation.
- [x] Q2 Pagination — "Load more", page size 20, auto-continue over up to 5 empty pages (AC-12, AC-13).
- [x] Q3 Polling — 30 s idle, 3 s while `RUNNING`, paused while the tab is hidden (AC-16).
- [x] Q4 Explicit search — on submit and once at load from the URL (AC-5, AC-6).
- [x] Q5 New data — banner only for runs that published data (`COMPLETED`, `PARTIAL`); a `FAILED` run shows a
  warning in the status panel instead (AC-15, AC-18).
- [x] Q6 Visualisation — price histogram + results by selected provider, which shows the aggregation result (AC-19).
- [x] Q7 Date control — a `<select>` of catalog dates with freshness (AC-3).
- [x] Q8 Port — `http://localhost:3000`, `FRONTEND_PORT` to override (AC-25).
- [x] Q9 Demo controls — runtime flag `DEMO_CONTROLS`, on by default in compose, panel clearly labelled as acting on
  gds-mock (AC-22).
- [x] Q10 Node — 26 (Active LTS) (AC-26).
- [x] Q11 npm dependencies — approved as listed, latest stable versions.
- [x] Q12 ADRs — ADR-0023 and ADR-0024.
- [x] Q13 Screenshot — a real one from the running stack; if browser automation does not work, the author takes it.
