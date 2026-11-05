# ADR-0014: Provider resilience with Resilience4j core, deadlines and per-provider breakers

- **Status:** Accepted
- **Date:** 2026-11-05
- **Related spec:** [specs/003-provider-ingestion](../../specs/003-provider-ingestion/spec.md)

## Context

The providers answer in 100–5000 ms and fail 10 % of requests with `500` (statement, spec 002); an outage answers
`503` at once. An ingestion run makes `providers × horizon days` requests (21 by default), each one subject to those
odds: without retries a provider loses at least one of its 7 day partitions in about half of the runs. Ingestion
runs in the background (ADR-0006), so waiting is cheap but hanging is not: runs never overlap, so a stuck run would
block every later one. Resilience must stay out of the domain and the use case (ADR-0002), and the build is Spring
Boot 4 / Java 21.

## Decision

We will make each attempt (one request plus parsing its whole body) go through, from the inside out:

1. **Timeouts:** connect `1s`; read `6s` for the first byte and for any gap between body bytes (above the 5 s
   maximum latency, so a slow but normal answer is waited for instead of retrying ~40 % of calls); a **total attempt
   deadline** of `60s` (≈ 10× a normal attempt) that aborts a trickling body.
2. **Circuit breaker per provider** (Resilience4j `CircuitBreaker`, count-based window of 10 attempts, minimum 10
   calls, opens at ≥ 80 % failures, `30s` open, 2 half-open probes). Failures are `5xx`, timeouts, I/O errors and
   malformed documents; `4xx` is ignored (our request, not the provider's health). With 10 % random errors,
   8 of 10 failures has p ≈ 4 × 10⁻⁷, so the breaker opens on outages, not on normal noise. Breakers are singletons
   and keep their state across runs. The **open wait (30 s) is well below the 15 min ingestion interval**
   (ADR-0006): it covers the rest of the run that detected the outage, so that run stops calling the provider, but
   the next scheduled run always finds the breaker ready to probe (half-open) instead of skipping a recovered
   provider. Two successful probes close it; a failed probe reopens it for another 30 s.
3. **Retry** (Resilience4j `Retry`) around the breaker: 3 attempts in total, exponential backoff 500 ms × 2 with
   ± 50 % jitter, only for retryable failures. `4xx` and an open breaker (`CallNotPermittedException`) are not
   retried.
4. **Run deadline** (`10m`, in the use case as plain Java): unfinished partitions are cancelled (their virtual
   threads interrupted, which closes blocked sockets) and reported as `RUN_TIMEOUT`.

We use the framework-free Resilience4j 2.4.0 core modules (`resilience4j-circuitbreaker`, `resilience4j-retry`; their
only dependency is `slf4j-api`), configured from our own properties in `infrastructure/config`. HTTP goes through
Spring `RestClient` with the body read as an `InputStream`.

## Alternatives considered

1. **`resilience4j-spring-boot3` + annotations** — targets Boot 3 auto-configuration and AOP proxies; we don't need
   either, and annotations would hide the per-attempt semantics we test.
2. **Spring Framework 7 `@Retryable` / `RetryTemplate` + a hand-written breaker** — viable and dependency-free, but a
   correct breaker (sliding window, half-open probes, thread safety) is non-trivial code to own and test.
3. **Short read timeout (e.g. 2 s) relying on retries** — cuts the latency tail but turns ~40 % of normal calls into
   retries, multiplying load on the providers for no benefit in a background job.
4. **No total deadlines** — a body that trickles one byte every few seconds would never hit the read timeout and
   could hang a run, and with it all future runs.

## Consequences

- **Positive:** a 10 %-error provider loses a partition with p ≈ 0.1 % instead of 10 %; an outage is detected in
  ≈ 3 s and then costs no requests; no run can take longer than 10 min; the domain and use case know nothing about
  any of this.
- **Negative / trade-offs:** the worst case per partition is ≈ 3 minutes (3 × 60 s); breaker state is per instance
  (not shared across replicas); retries re-download a whole partition (no resume).
- **Follow-ups:** expose breaker state and attempts through the ingestion status endpoint (next spec); metrics via
  Micrometer if needed.
