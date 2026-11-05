# ADR-0012: One gds-mock application exposing the three providers

- **Status:** Accepted
- **Date:** 2026-11-05
- **Related spec:** [specs/002-gds-mock](../../specs/002-gds-mock/spec.md)

## Context

The aggregator integrates three external GDS providers (Mock-Alpha JSON, Mock-Beta XML, Mock-Gamma CSV) that are
slow, fail now and then, and must be able to fail independently (one provider down must not stop ingestion). They
don't exist, so we build a test double. Its data must be consistent **across** providers: the same itinerary offered
by two providers must have the same schedule, or deduplication can't be exercised. It must boot with
`docker compose up --build` and stay cheap to build and maintain.

## Decision

We will build **one Spring Boot application, `gds-mock`**, that exposes each provider on its own endpoint
(`GET /{providerId}/flights`) with its own format, and keeps **chaos state per provider** (latency range, error
rate, outage switch). It has no dependency on `flight-aggregator`.

- All providers read the same generated world (ADR-0013), so cross-provider duplicates are consistent by
  construction.
- Independence is simulated per provider: separate settings and an outage switch on an admin API, so one provider
  can be slow, failing or down while the others answer.
- The aggregator configures **one base URL per provider**. Splitting the mock into three deployments later would be
  a configuration change on the aggregator side, not a code change.
- Inside the app, a provider is one `ProviderFormat` class (serializer). Chaos is a separate concern (an interceptor
  plus a framework-free policy), not mixed into serializers or controllers.

## Alternatives considered

1. **Three applications / containers, one per provider** — closer to reality, but it triples the build, images and
   compose services, and each app would have to regenerate the same world. The aggregator can't tell the
   difference, so there is no behavioural gain. Rejected.
2. **Static fixture files served by a generic HTTP mock (WireMock, nginx)** — simple, but it can't produce 100k–1M
   records, configurable fractions of bad data or per-request randomness, and the files would be huge. Rejected.
3. **Mocking providers inside the aggregator's tests only** — needed for unit tests anyway, but it gives no
   runnable system for the demo or for `docker compose`. Rejected as the only mock.

## Consequences

- **Positive:** one image, one healthcheck, one place to control the demo (outage switch); consistent duplicates;
  provider independence where it matters (failures and latency).
- **Negative / trade-offs:** the providers share a JVM, so a crash or GC pause affects all three at once, which is
  less independent than real providers. The admin API is unauthenticated (it's a mock).
- **Follow-ups:** the ingestion spec configures three base URLs and per-provider timeouts and retries.
