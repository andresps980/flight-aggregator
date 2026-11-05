# 005 — Provider ingestion: streaming adapters, resilience, per-day aggregation (spec 003)

- **Date:** 2026-11-05
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `feat/003-provider-ingestion` (PR 5 of the repository)

## Prompts

### 1. Kick-off

````text
We continue the assessment. Read CLAUDE.md, docs/exercise.md, SOLUTION.md, docs/adr/, docs/ai/,
docs/providers.md and specs/ (especially the follow-ups noted in specs 001 and 002) before
planning. Same conventions as previous PRs: everything in English, SDD workflow, SOLID applied
pragmatically, record this session in docs/ai/ (prompt, summary, skills/agents used, review
findings, notable corrections) and update SOLUTION.md where relevant. This is PR 5 in the repo.

Goal: the ingestion layer of flight-aggregator — three provider adapters (streaming parsers)
and a parallel, resilient ingestion use case that feeds the spec 001 domain and produces an
ingestion report. No database, no scheduling and no REST endpoint yet (next PR): results go
through an outbound port with an in-memory adapter, so the next PR only adds a PostgreSQL adapter.

1. Run /spec-feature to write specs/003-provider-ingestion/spec.md. Cover at least:
   - Adapters for Mock-Alpha (JSON), Mock-Beta (XML) and Mock-Gamma (CSV) that parse the
     response as a stream (Jackson 3 streaming parser, StAX, line reader — never the whole
     document in memory) and normalize to spec 001 types: all three time representations to
     Instant, flight numbers (zero-padded, separate carrier), prices to Money.
   - Malformed records (bad IATA, non-positive price, unparseable time, non-numeric flight
     number) are counted per provider and reason and skipped; they never abort the feed.
     A structurally broken or truncated document fails the whole partition.
   - XML parsing is XXE-safe (DTDs and external entities disabled), tested.
   - Ingestion requests every (provider, day) partition of the configured horizon, all
     providers in parallel (virtual threads), with bounded concurrency per provider.
   - Resilience per provider: per-request timeout, retries with exponential backoff and
     jitter only for 5xx/timeouts/IO errors (not 4xx), and a circuit breaker per provider so
     an outage (503) stops further calls to that provider quickly. Prefer the framework-free
     Resilience4j core modules (circuitbreaker, retry) used from the adapters/infrastructure;
     verify compatibility with Spring Boot 4 / Java 21 first, and if it is a problem propose an
     alternative (e.g. Spring Framework 7 resilience features + a small breaker) in an ADR.
   - Partial results: a failed partition or provider never fails the whole ingestion; the
     report says what is missing. Only an ingestion where every provider failed is FAILED.
   - Partition atomicity: offers from a partition reach the aggregator only if that partition
     completed successfully (an attempt that fails mid-stream must not leave half its offers
     in the result, and a retry must not duplicate them). Propose how (e.g. buffer per
     partition, bounded by partition size) and state the memory trade-off.
   - The OfferAggregator is single-use and not thread-safe: define how parallel partitions
     feed it safely (e.g. single consumer, lock, or partitioned aggregators whose stats add up).
   - Ingestion report: overall status (COMPLETED / PARTIAL / FAILED), per provider status,
     partitions succeeded/failed, attempts, durations, circuit breaker state, offers received,
     malformed per reason, plus the spec 001 aggregation statistics. When nothing fails, the
     figures must reconcile exactly with gds-mock's /admin/world/stats for the same settings.
   - Configuration: provider base URLs, horizon, timeouts, retry and breaker settings, with
     sensible defaults justified against the mock's 100–5000 ms latency and 10% error rate.
   Show me the spec with open questions and wait for my approval.

2. Design guidance (justify in the plan; /adr for significant choices: resilience strategy and
   values, partition atomicity/memory, how parallel feeds reach the aggregator):
   - application layer: an ingestion use case (framework-free) depending on outbound ports
     (provider feed per provider, aggregated itinerary sink); adapters in
     infrastructure/adapter/out/provider/{alpha,beta,gamma}; resilience lives in
     infrastructure, never in domain or application.
   - New provider = new adapter class + configuration, no change to the use case.
   - HTTP client: Spring RestClient (or the JDK HttpClient) reading the body as an
     InputStream so parsing is truly streamed.

3. After I approve the spec, /implement-spec 003 on feat/003-provider-ingestion. Tests first:
   - Parser unit tests with fixture payloads per format: valid records, each malformed kind,
     time-zone normalization (including a connection across zones), zero-padded and split
     flight numbers, empty feed, truncated document, XXE attempt.
   - Resilience ITs with WireMock (use a version compatible with Spring Boot 4 / Jetty 12,
     e.g. the standalone artifact): latency above the timeout then success on retry; 500 then
     success; persistent 500 => partition failed, others fine; 503 outage => breaker opens and
     short-circuits; one provider down => PARTIAL with the other two; all down => FAILED
     report, no exception; mid-stream failure => no partial offers in the result; providers
     run in parallel (total time close to the slowest, not the sum).
   - Keep test timings small (scale timeouts/latencies down in test config; no 5 s sleeps)
     and avoid flaky timing assertions.
   - An optional end-to-end IT tagged "e2e" (excluded from the default verify) that runs
     against a running gds-mock in the deterministic profile and asserts the report reconciles
     with /admin/world/stats. Document how to run it; the CI smoke job will run it in a later PR.
   After writing the tests (before implementing), stop and show me the list grouped by AC.

4. Run code-reviewer and spec-reviewer, fix findings, record the main ones in docs/ai/.
   Update docs/architecture.md (ingestion flow diagram), docs/usage.md (configuration table)
   and SOLUTION.md (resiliency section, memory trade-offs, limitations).

5. ./mvnw verify must be green; report its duration. Then prepare the PR with /pr-description,
   and after pushing, check CI and the code scanning findings on the PR as we did last time.
````

### 2. Spec approval with changes

````text
Good spec. Approve it with these changes:

1. Aggregate per day instead of one global aggregator under a lock. The itinerary identity
   includes departure instants, so duplicates can only occur within the same day partition
   across providers. For each horizon day: fetch that day from all providers (in parallel,
   with the per-provider concurrency limit), feed a dedicated OfferAggregator, publish that
   day's result, release it. Days can run concurrently up to the configured limit. Sum the
   per-day statistics (spec 001 was designed for additive partitioned aggregators).
   Document the assumption that providers partition by UTC date of first departure (true for
   gds-mock). Recompute the memory estimate: bounded by the largest day, not the whole world,
   and measure it in this PR.
2. Q1 yes. Q2 yes. Q3 yes (background ingestion; a short timeout would retry ~40% of calls).
   Q4 yes. Q7 yes.
3. Q5 yes, publish PARTIAL — and add a requirement for the persistence spec: a day missing a
   provider must not silently wipe that provider's previous good data for that day.
4. Q6 no: add a total deadline per attempt (e.g. 60 s) and per run, so a trickling body
   can't hang a run forever (with no concurrent runs, that would block all future runs).
5. Circuit breaker thresholds must not open under the mock's normal 10% error rate, only on
   sustained failure/outage. Add a test with a fixed seed and 10% errors asserting it stays
   closed, plus the outage test asserting it opens.

Then mark the spec Approved, write the ADRs and continue with the tests; stop with the test
list grouped by AC as before.
````

### 3. Test review checkpoint

````text
Go ahead with the implementation, with these additions:

1. Update the testing rule in CLAUDE.md instead of bending it: adapter-level ITs may build the
   adapter directly against WireMock without a Spring context; end-to-end flows use @SpringBootTest.
2. Make sure the breaker's wait duration in open state (before half-open) is well below the
   15-min ingestion interval (e.g. 30–60 s), state it in ADR-0014 and test half-open recovery
   with it.
3. If the adapter receives the body stream, add a simple test with an InputStream wrapper that
   records close() for success, malformed-record and broken-document outcomes; otherwise leave
   it to code review.
4. Document Gamma's CRLF / blank-line tolerance in docs/providers.md.
5. Widen IngestionParallelismIT's upper bound to ~3.5 s to avoid CI flakiness (barriers already
   prove the concurrency).
6. After measuring memory at 1M, set the default days-in-parallel and the container heap
   (Dockerfile JVM options) accordingly, and record the numbers in SOLUTION.md.
````

## Clarifications (open questions of the spec)

The agent's proposal is in bold.

| # | Question | Decision |
|---|---|---|
| Q1 | Strip leading zeros of flight numbers in the domain (`FlightNumber`) rather than per adapter (**domain**) | Yes |
| Q2 | Default 2 partitions in flight per provider (**2**) | Yes; the author added per-day aggregation with `max-concurrent-days` |
| Q3 | Read timeout 6 s, above the 5 s maximum latency (**6 s**) | Yes |
| Q4 | Retry a malformed/truncated document and count it as a breaker failure (**yes**) | Yes |
| Q5 | Publish `PARTIAL` results, not `FAILED` ones (**yes**) | Yes, plus a requirement for the persistence spec: don't wipe a missing provider's previous data for the day |
| Q6 | No total deadline per attempt or run (**accept as a limitation**) | **Overruled:** 60 s per attempt, 10 min per run |
| Q7 | Extra malformed reasons `BAD_FLIGHT_NUMBER`, `INVALID_RECORD` (**yes**) | Yes |

The author also changed the aggregation design (one aggregator per day instead of one global aggregator under a
lock) and required breaker thresholds that never open at the mock's normal 10 % error rate, proven by a seeded test.

## Skills, agents and hooks involved

- Skills: `spec-feature` (spec 003), `adr` (ADR-0014, 0015, 0016), `implement-spec`, `pr-description`.
- Agents: `code-reviewer` and `spec-reviewer`, run in parallel on the branch.
- Hooks: `format-java.sh` (Spotless after each edit), `verify-on-stop.sh`, `guard-git.sh`.

## What was done

- Checked compatibility before choosing libraries. Resilience4j 2.4.0 core modules depend only on `slf4j-api`.
  `wiremock-standalone` 3.13.2 shades its Jetty, which a throwaway spike test confirmed on the Boot 4 classpath.
- Spec 003 (23 acceptance criteria) and three ADRs.
- Recorded a small deterministic set of real gds-mock feeds with its `/admin/world/stats` (seed 42, 1,400
  itineraries) as test fixtures, so exact reconciliation runs in every build without a running mock.
- Wrote the tests first and showed them to the author grouped by AC before implementing.
- Implemented the three streaming parsers, `HttpProviderFeed` (timeouts, attempt deadline, breaker, retry), the
  per-day `IngestionService`, the in-memory sink, validated `aggregator.*` properties, and the domain changes
  (`FlightNumber` canonical form, `AggregationStatistics.plus`).
- Ran the `e2e` test against a real gds-mock: 100,000 and 1,000,000 itineraries reconcile exactly.
- Measured memory at 1M (SOLUTION.md) and set the defaults and the new `flight-aggregator/Dockerfile` heap from the
  results.

## Notable corrections during the session

- **The attempt deadline relied on the caller being a virtual thread.** It interrupted the calling thread, which
  only closes a blocked socket on a virtual thread, and the feed IT calls `fetch` from a platform thread, so a
  trickling body ran to completion. Each attempt now runs on its own virtual worker thread, and the caller waits for
  it with the deadline.
- **A partition could start a request after the run deadline.** It was waiting for its provider's permit and got
  it when the cancelled request released it. It now gives up. A unit test that had passed by luck (it assumed
  which day would get the permit first) became deterministic.
- **`OutOfMemoryError` was disguised as a partition failure** (found while measuring memory). Rethrowing alone would
  have made the day's coordinator wait for the run deadline. Every partition now always delivers an outcome, and the
  `Error` is rethrown.
- **`HttpURLConnection` re-sends a GET once after a connection reset,** so a single reset never reached our retry
  and the "reset then success" test saw 1 attempt. It was replaced by a persistent-reset test, and the limitation is
  documented.
- **The binder can build nested property records with zero values,** so validating inside the nested record failed
  every startup. Validation moved to the enclosing record.
- **The docs had an arithmetic error.** `docs/providers.md` (spec 002) said OPO 12:55 Lisbon → BOS 12:15 New York is
  6 h 20 min. On 6 November it is 4 h 20 min, because the US left DST on 1 November. The parser test caught it; the
  doc and spec 003 were fixed.
- **A memory surprise:** too little heap degrades into `PARTIAL` runs (GC pauses cause read timeouts) before it
  causes out-of-memory errors. That is now recorded as a limitation.

## Review findings and how they were handled

Both agents ran in parallel on the branch after the implementation was green.

**code-reviewer** (verdict: *Needs changes*, nothing blocking). It confirmed the architecture rules, the XXE setup and
the concurrency invariants (only coordinators write tallies, permits released in `finally`, interrupt flags
restored, the executor waits for cancelled tasks). Fixed:
- **An interrupt during the retry backoff was reported as the previous attempt's failure.** Resilience4j re-sets
  the flag and rethrows the last exception (e.g. a `500`), so the `RUN_TIMEOUT` branch never ran. The interrupt flag
  is now checked first. New test: `500`, then an interrupt during a 5 s backoff → `RUN_TIMEOUT`, 1 request.
- **One bad field could fail a whole Beta document.** `getElementText()` throws on a child element, which made the
  partition `MALFORMED_DOCUMENT`, retried and counted by the breaker. A bounded text reader now turns it into one
  `INVALID_RECORD`. Tested.
- **Memory had no bound on hostile input except in Gamma.**
  - Alpha keeps only the fields it uses (unknown ones are skipped without being materialised), with Jackson stream
    constraints of 64 KiB per string and nesting depth 32, and at most 16 legs per record.
  - Beta: 64 KiB per text, at most 16 segments per itinerary.
  - New `max-records-per-partition` (500,000) caps any attempt's buffer.
  - Tested per parser and over HTTP.
- **A sink exception discarded the whole report.** A failed publication is now logged and reported as
  `published = false`; the run goes on. Tested.
- **Missing tests:** the run deadline over real HTTP (`IngestionRunDeadlineIT`: returns in < 3 s, Beta is
  `RUN_TIMEOUT`, no request starts after the run) and days that never start (unit test).
- **A flaky wait:** `interruptionAbortsFetch` slept 300 ms before interrupting. It now polls WireMock until the request
  arrived, and also checks the failure kind.
- **Nice to have, also done:**
  - Outcomes delivered at the deadline are drained instead of reported as `RUN_TIMEOUT`.
  - The day semaphore is fair, so near days tend to run first.
  - A WARN when an aborted worker outlives its join.
  - A cancellation during an abort is no longer reported as `TIMEOUT`.
  - The breaker ignores bugs and `Error`s instead of counting them as successes (tested).
  - Base URLs must be absolute `http(s)` (tested).
  - The half-open IT uses a controllable clock instead of sleeping.
  - Inline fully qualified names and a stale Javadoc were cleaned up.

**spec-reviewer** (verdict: *Gaps found*, all minor). Every AC from AC-1 to AC-23 is implemented and named by at least
one test; the defaults match the spec. Acted on:
- **AC-13 wording.** The spec promised "no request outlives the run", but an aborted request is awaited for at most one
  read timeout. The spec, ADR-0014 and SOLUTION.md now state the bound.
- **Startup tests** now cover every validated setting (25 cases), including the new ones.
- **Doc wording:**
  - The spec's compose base URL was presented as existing when it doesn't yet.
  - The new types live in `ingestion.domain`, not the application layer.
  - SOLUTION.md said "one record held at a time", but a partition is buffered.
  - The 1M e2e run was manual, and is now described that way.
  - ADR-0015 claimed a per-buffer measurement that wasn't made.

**Not changed, with reasons:**
- *"Dockerfile and CLAUDE.md are scope creep."* The author asked for both (the test-checkpoint message, items 1
  and 6).
- *"Test that a partition is fed as soon as it completes"* (AC-10). No seam makes that observable from outside without
  adding a test-only hook to production code. It is a structural property of the coordinator's loop and is left to
  review.
- *Inject the application `Clock` into the breakers.* The ITs fix that clock, so the open wait would never elapse. The
  breakers use the wall clock on purpose (commented).
- *Raise the parallelism IT bound above 3.5 s.* The author fixed it at 3.5 s, and the barrier tests prove the
  concurrency.
