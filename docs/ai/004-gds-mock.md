# 004 — gds-mock: mock GDS providers with chaos (spec 002)

- **Date:** 2026-11-05
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `feat/002-gds-mock` (PR 4 of the repository)

## Prompts

### 1. Kick-off

````text
We continue the assessment. Read CLAUDE.md, docs/exercise.md, SOLUTION.md, docs/adr/, docs/ai/
and specs/ before planning. Same conventions as previous PRs: everything in English, SDD
workflow, SOLID applied pragmatically (abstractions only where there is real variation),
record this session in docs/ai/ (prompt, summary, skills/agents used, notable corrections),
and update SOLUTION.md where relevant.

Goal of this PR: the `gds-mock` module — a standalone Spring Boot app that simulates the three
external GDS providers. It is a test double of external systems, so it must NOT depend on the
flight-aggregator module or share its domain classes. Its data must exercise the aggregator:
cross-provider duplicates, invalid itineraries, malformed records, latency and errors.

1. Run /spec-feature to write specs/002-gds-mock/spec.md. Cover at least:
   - Three providers, each with its own legacy format and endpoint:
     * Mock-Alpha: raw JSON list (flat-ish objects, legacy field names, e.g. epoch-millis times).
     * Mock-Beta: deeply nested XML (e.g. response > itineraries > itinerary > pricing /
       origin-destination options > segments), times as ISO-8601 with offset.
     * Mock-Gamma: comma-separated string stream, one itinerary per line, segments encoded in
       a sub-delimited field, times as local date-time plus airport timezone or offset.
     Different time representations per provider are intentional (adapters must normalize).
   - Deterministic data generator (seeded): a shared "world" of itineraries over a configurable
     horizon (e.g. next 7 days) and volume (default ~100k combinations, configurable up to
     1,000,000). Each itinerary is offered by 1–3 providers with different prices, so
     deduplication is meaningful. Same seed + same date => same data.
   - Configurable fractions of business-invalid itineraries (segments that don't chain,
     connection < 45 min, layover > 24 h, arrival before departure, already departed, mixed
     airlines, revisited airport) and of malformed records (bad IATA code, non-positive price,
     unparseable time), matching spec 001's rules.
   - Streaming responses (no full materialization in memory) so 1M records can be served:
     streamed JSON array, StAX-written XML, line-by-line CSV.
   - Chaos per provider: random latency in a configurable range (default 100–5000 ms), a
     configurable error rate returning HTTP 500, and an admin endpoint to force a provider
     outage/recovery at runtime (useful for the live demo of resiliency). A deterministic
     test profile with zero latency and zero errors.
   - Health endpoint, and documentation of each format with a small example payload.
   Show me the spec with open questions and wait for my approval.

2. Design guidance (justify in the plan; use /adr for significant choices, e.g. one app with
   three provider endpoints vs three services, and how determinism/volume are achieved):
   - Generator as plain, framework-free code; one serializer per provider behind a common
     interface (new provider = new class); chaos (latency/errors/outage) as a separate concern,
     not mixed into serializers or controllers.
   - Adapt ArchUnit rules for this module's layout as needed.

3. Packaging: multi-stage Dockerfile for gds-mock (build context = repo root, multi-module
   build), and a first docker-compose.yml containing only gds-mock with a healthcheck. The
   full stack is added in later PRs.

4. After I approve the spec, run /implement-spec 002 on feat/002-gds-mock: tests first
   (generator determinism and fractions, each serializer's output parses back correctly,
   streaming endpoints, chaos behaviour with injected randomness/clock, admin outage).
   After writing the tests (before implementing), stop and show me the list of tests grouped
   by acceptance criterion.

5. Run the code-reviewer and spec-reviewer agents, fix findings, record the main ones in
   docs/ai/. Update docs/usage.md (how to run the mock, endpoints, chaos and admin controls)
   and add docs/providers.md with the format examples.

6. ./mvnw verify must be green and `docker compose up --build` must start gds-mock healthy.
   Then prepare the PR with /pr-description.
````

### 2. Plan approval with changes (the spec's open questions were answered here)

````text
Plan approved with these changes:

1. Q7: include same-provider duplicates as a small configurable fraction (default 0.5%),
   since spec 001 covers them; keep mid-stream truncation and gzip out of scope.
   Adjust AC-4 accordingly.
2. Add GET /admin/world/stats returning the expected figures for the current settings
   (offers per provider, invalid per kind, malformed per provider, cross- and same-provider
   duplicates, distinct itineraries). It will be used by the ingestion PR to reconcile counts.
   Add an AC and tests for it.
3. Docker: make sure the runtime image is multi-arch (arm64 for Apple Silicon); if
   eclipse-temurin:21-jre-alpine isn't, use eclipse-temurin:21-jre. Make sure the
   healthcheck tool exists in the runtime image.
4. Spec: state explicitly which horizon day the injected ALREADY_DEPARTED itineraries are
   served under when ?date= is used.
5. Spec consequences section: note that per-day partitioning means ~7 requests per provider
   per ingestion, each subject to the error rate, so the ingestion (next spec) must retry per
   partition and accept partial results.
6. This is PR 4 in the repository, not PR 3 — use the right number in docs/ai and the PR.

Q1–Q6 and Q8: yes as proposed.
````

### 3. Test review checkpoint

````text
Test list approved with these changes before implementing:

1. No flaky tests: every statistical/random test must use a fixed seed, including the
   "real random source ~10% of 10,000" case in AC-16. DefaultProfileIT must only check
   configuration and health, never call provider feeds (default latency/errors).
2. Build time: prove streaming with a smaller volume (probe before the writer finishes);
   move the 1M-record tests to @Tag("slow"), excluded from the default verify and runnable
   via a Maven profile (document it). The manual -Xmx256m check covers real volume.
   Report the total ./mvnw verify time at the end.
3. Close the AC-20 gap: /admin/world/stats also reports the number of itineraries whose
   records are all malformed (per provider and overall), so WorldStatsIT can assert exact
   figures instead of a lower bound.
4. Add a concurrency IT: request the three providers in parallel with fixed latencies and
   assert total time is close to the slowest, not the sum.
5. Handle client aborts: if the client disconnects mid-stream, stop generating and don't log
   it as an error; add a test if feasible.

Then continue with the implementation.
````

## Clarifications (open questions of the spec)

The agent's proposal is in bold; all were accepted unless noted.

| # | Question | Decision |
|---|---|---|
| Q1 | `volume` = distinct itineraries over the whole horizon (**yes**) | Yes |
| Q2 | Horizon starts the day after the reference date, so only injected itineraries are departed (**yes**) | Yes |
| Q3 | `/{providerId}/flights` with an optional `date` filter (**yes**) | Yes |
| Q4 | Forced outage → immediate `503`; random errors → `500` after the latency (**yes**) | Yes |
| Q5 | Defaults 5 % invalid, 1 % malformed, 10 % errors, 40/35/25 % providers per itinerary (**yes**) | Yes |
| Q6 | Flight numbers differ per provider on purpose (Beta split, Gamma zero-padded) (**yes**) | Yes |
| Q7 | Same-provider duplicates, mid-stream truncation and gzip out of scope (**out**) | Author overrode: same-provider duplicates **in** (0.5 %) |
| Q8 | Unauthenticated admin API that only toggles the outage (**yes**) | Yes |

The author also added the stats endpoint, the `ALREADY_DEPARTED` partition rule, the consequences section, the
multi-arch requirement (plan review), and at the test checkpoint: seeded tests, a `slow` tag, exact fully malformed
figures, a concurrency IT and quiet client aborts.

## Skills, agents and hooks involved

- Skills: `spec-feature` (spec 002), `adr` (ADR-0012, ADR-0013), `implement-spec`, `pr-description`.
- Agents: `code-reviewer` and `spec-reviewer`, run in parallel on the branch while the docs were written.
- Hooks: `format-java.sh` (Spotless after each edit), `verify-on-stop.sh`, `guard-git.sh`.

## What was done

- Spec 002 (22 acceptance criteria) and two ADRs: one app for the three providers (ADR-0012); stateless,
  index-seeded, streamed generation (ADR-0013).
- `gds-mock` module: a framework-free generator (`WorldGenerator`, `ScheduleFactory`, `OfferFactory`), one
  `ProviderFormat` per provider (Jackson 3 streaming, StAX, CSV), chaos as a policy plus an interceptor, the outage
  admin API, and `GET /admin/world/stats` with the expected spec 001 aggregation.
- Tests written first and reviewed by the author before implementation. Each acceptance criterion is checked
  against a test-local re-implementation of spec 001's rules and against independent client-side parsers, never
  aggregator code. `WorldStatsIT` reconciles every figure exactly with what a client parses from the three feeds.
- Multi-stage, layered, multi-arch Docker image (checked healthy on arm64, and on amd64 under emulation), and a
  compose file with only gds-mock.
- Measured: a 1,000,000-itinerary world served under `-Xmx256m` (229 MiB container) with no warnings; numbers in
  `SOLUTION.md`. `./mvnw verify` for the whole repository: about 23 s.

## Review findings and how they were handled

**spec-reviewer** (verdict: *Spec satisfied*, all 22 ACs implemented and tested, no drift or scope creep). Acted on:
- **`ALREADY_DEPARTED` identities could collide.** They all depart on the same day, and the same index on two
  partition days shares airline and first flight number. A partition-specific departure slot
  (`slot mod horizonDays = dayIndex`) now makes identities unique by construction. A test at invalid fraction 1
  covers it. The code reviewer reported the same issue.
- Startup errors now name the full property (`gds.world.volume`, `gds.chaos settings for mock-beta …`).
- The spec now states that request validation (400/404) wins over an outage (503).
- amd64 was not verified, so the image was built and run under emulation: healthy.

**code-reviewer** (verdict: *Needs changes*, nothing blocking). Fixed:
- **Serializer bugs could pass for client disconnects.** Formats wrapped every failure in an `IOException` and the
  controller logged them all at DEBUG. Now only real I/O failures are `IOException`s, and only disconnects (Spring's
  `DisconnectedClientHelper`) are DEBUG. Other output failures are WARN. New tests cover both.
- **A failed Alpha feed could be closed into valid JSON.** Jackson's `AUTO_CLOSE_CONTENT` would write the missing
  `}]` on close, so a truncated feed would parse as complete. It is now disabled; a test checks that an interrupted
  feed doesn't parse.
- **Stats were computed while holding a lock.** `computeIfAbsent` ran the 1.7 s pass inside the map lock, which pins
  virtual threads' carriers on JDK 21. The first caller now inserts a future and computes outside the lock; other
  callers wait on it. A new `WorldStatisticsCacheTest` covers once per key, eviction, concurrent callers and failures.
- **Per-provider overrides typed as environment variables were silently ignored**, because env vars drop the dash in
  map keys. Unknown provider ids in `gds.chaos.providers` now fail the startup, and the docs explain how to pass
  overrides.
- **Smaller fixes:**
  - The horizon is read once per request, and the feed's date is checked before the response starts.
  - Timing ITs have upper bounds: `ChaosIT` < 600 ms catches double latency end to end; `ConcurrencyIT` < sum.
  - A test covers the per-day limit (NH9999).
  - Gamma pads flight numbers by hand instead of `String.format`.
  - The Docker image has jar layers, a numeric uid, a single healthcheck (in compose) and a smaller build context.
  - The CSV line ending is documented.

**Not changed:**
- A catch-all exception handler: it would also capture framework 404/405s and client disconnects. Recorded as a
  limitation in `SOLUTION.md`.
- An IT proving generation stops after an abort: a 300k feed finishes in about a second on loopback, so a timing
  assertion would be weak. The format contract test proves records stop being consumed.
- Moving `FeedRequestResolver` to `shared`: it depends on the inventory's `Horizon`. The chaos → inventory
  dependency only goes one way, and ArchUnit checks it.

## Notable corrections during the session

- **Latency was applied twice per feed.** Streamed responses are asynchronous, and Spring dispatches the request a
  second time when the body completes, so the chaos interceptor ran again. That doubled the latency and could inject
  a `500` after a feed had been sent. The concurrency IT the author asked for exposed it (≈ sum instead of ≈ slowest).
  The agent first suspected the server was serialising requests, then measured time to first byte with curl and
  found the real cause. The interceptor now ignores `ASYNC` dispatches, with unit and end-to-end tests.
- **`SpringApplicationBuilder.properties()` sets defaults, not overrides.** The startup-validation IT passed invalid
  values that `application.yml` silently replaced. It now uses command-line arguments.
- **A test assumed something false about the data.** It asserted that an itinerary with a malformed record always
  has a well-formed one. Two malformed records on the same itinerary are legitimate: that is exactly the "fully
  malformed" case the author asked the stats to report. The test now checks independence (more than 95 %).
- The test-first checkpoint again left the build red until implementation, as expected after session 003.
