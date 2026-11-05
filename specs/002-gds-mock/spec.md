# Spec 002: GDS mock providers

- **Status:** Implemented
- **Branch:** feat/002-gds-mock
- **Related ADRs:** [ADR-0012](../../docs/adr/0012-single-gds-mock-application.md),
  [ADR-0013](../../docs/adr/0013-stateless-seeded-streamed-generation.md),
  [ADR-0006](../../docs/adr/0006-scheduled-pre-ingestion.md), [ADR-0010](../../docs/adr/0010-domain-assumptions.md)

## Context

From the [statement](../../docs/exercise.md): the system ingests itineraries from three GDS providers —
*"Alpha uses raw JSON lists, Beta uses deeply nested XML structures, Gamma uses comma-separated string streams"* —
that *"have artificial latencies (ranging from 100ms to 5000ms) and occasional 500 errors"*, for an inventory of
100,000 – 1,000,000 combinations per day.

`gds-mock` is the test double of those external systems. It is a standalone Spring Boot app that **does not depend on
`flight-aggregator` or share its classes**. Its data must exercise the aggregator: cross- and same-provider
duplicates with different prices, business-invalid itineraries (one per spec 001 discard reason), malformed records,
latency, errors and outages. It also publishes the figures the aggregator is expected to produce, so the ingestion
spec can reconcile its counts.

## Scope

**In scope**
- One app exposing three provider endpoints, each with its own legacy format and time representation.
- A seeded, deterministic generator of a shared "world" of itineraries over a configurable horizon and volume.
- Injection of business-invalid itineraries, malformed records and same-provider duplicates (configurable fractions).
- Streaming responses (nothing materialised in memory) up to 1,000,000 itineraries.
- Chaos per provider: random latency, random `500`s, and a runtime outage switch on an admin API.
- `GET /admin/world/stats`: expected figures for the current settings.
- A `deterministic` profile (no latency, no errors), health endpoint, multi-stage Dockerfile and a first
  `docker-compose.yml` with only `gds-mock`.
- Documentation of each format with an example payload (`docs/providers.md`).

**Out of scope**
- The aggregator's provider adapters and ingestion (next spec).
- Mid-stream failures (truncated responses), gzip compression, authentication on the admin API.
- Runtime changes of latency or error rate (only the outage switch is runtime).
- The full stack in `docker-compose.yml` (later PRs).

## Definitions

- **Reference date** — today in UTC from the injected `Clock`, or `gds.world.reference-date` when set.
- **Horizon** — the `horizon-days` UTC days that start the day **after** the reference date (default 7). Starting
  tomorrow means the only departed flights are the injected `ALREADY_DEPARTED` ones.
- **World** — `volume` distinct itineraries, split evenly over the horizon days. Each one is generated for exactly
  one horizon day: its *partition day*.
- **Identity** — as in spec 001: flight numbers (with airline), segment airports and departure instants. Flight
  numbers are compared after normalisation (no leading zeros).
- **Record** — one provider's offer of an itinerary: one JSON object, one XML `itinerary` element or one CSV line.

## Acceptance criteria

**World and determinism**
- **AC-1** — Given the same seed, reference date and settings, when a provider endpoint is called twice (or by two
  app instances), then the responses are byte-identical. Given a different seed, the data differs.
- **AC-2** — Given the default settings, when the world is generated, then every itinerary belongs to one of the 7
  horizon days, and every itinerary except `ALREADY_DEPARTED` ones departs (first segment) within its partition day
  (UTC).
- **AC-3** — Given `volume` V, then the world contains exactly V distinct identities (unique by construction, also
  among `ALREADY_DEPARTED` itineraries, which all depart the same day). Default 100,000; allowed
  1..1,000,000, with `horizon-days` 1..30 and at most 239,976 itineraries per day (24 airlines × 9,999 flight
  numbers). Settings out of range fail the startup with a message naming the property.
- **AC-4** — Each itinerary is offered by 1, 2 or 3 providers (about 40 / 35 / 25 %), with identical schedules and
  prices varied independently per provider. A provider repeats an itinerary only as an injected **same-provider
  duplicate**: a configurable fraction of records (default 0.5 %) gets one extra record from the same provider with
  a different price and its own record id.
- **AC-5** — Valid itineraries have 1–3 segments of one airline, chained, with connections between 45 min and 24 h
  (mostly 1–6 h), every arrival after its departure and no airport visited twice.

**Invalid and malformed data**
- **AC-6** — Given `invalid-fraction` F (default 0.05), then about F of the itineraries (±0.5 pp at 100,000) are
  business-invalid, each with exactly one kind, spread about evenly: `MIXED_AIRLINES`,
  `ARRIVAL_NOT_AFTER_DEPARTURE`, `SEGMENTS_NOT_CHAINED`, `REVISITED_AIRPORT`, `CONNECTION_TOO_SHORT`,
  `LAYOVER_TOO_LONG`, `ALREADY_DEPARTED`. The kind is the **first failing rule in spec 001's evaluation order**
  (AC-20 there), so discard counts can be reconciled. Invalid itineraries are offered and duplicated like valid ones.
  `ALREADY_DEPARTED` itineraries depart on the reference date − 1 but are served under their partition day. With
  F = 0 there are none.
- **AC-7** — Given `malformed-fraction` M (default 0.01), then about M of the records (±0.2 pp at 100,000
  itineraries) are malformed with one kind: `BAD_AIRPORT_CODE` (not 3 letters), `NON_POSITIVE_PRICE` (0 or
  negative) or `UNPARSEABLE_TIME` (format-specific garbage). Only that record is broken: the document still parses,
  and the other providers' records of the same itinerary are unaffected.

**Formats and streaming** (examples in *API contract*)
- **AC-8** — Mock-Alpha answers `application/json`: a raw array of flat-ish objects with legacy field names, times
  as epoch milliseconds (UTC), flight numbers with carrier prefix (`IB345`).
- **AC-9** — Mock-Beta answers `application/xml`: `response > itineraries > itinerary` with `pricing` and
  `originDestinationOptions > option > segments > segment`; times ISO-8601 with the airport's UTC offset; carrier and
  flight number in separate elements.
- **AC-10** — Mock-Gamma answers `text/csv`: a header line, then one itinerary per line; the segments field holds
  `|`-separated segments with `~`-separated sub-fields; times are local date-times with the airport's IANA zone id;
  flight numbers zero-padded to 4 digits (`IB0345`).
- **AC-11** — When a response is parsed, then it yields exactly that provider's records of the world, in generation
  order: same flights, airports, instants and prices; malformed records differ only in their injected field.
- **AC-12** — Responses are streamed while they are generated (chunked, no `Content-Length`); nothing proportional to
  the volume is held in memory, so a 1,000,000-itinerary world is served with a bounded heap. If the client
  disconnects mid-stream, generation stops and the disconnect is not logged as an error.
- **AC-13** — Given `?date=YYYY-MM-DD` inside the horizon, then only itineraries of that partition day are returned;
  the partitions are disjoint and their union is the unfiltered response.
- **AC-14 (error)** — A `date` outside the horizon or not in `YYYY-MM-DD` ⇒ `400`; an unknown provider ⇒ `404`. Both
  as RFC 9457 Problem Details, without latency or errors applied. Request validation comes first: a bad request to a
  provider in outage gets `400`/`404`, not `503`.

**Chaos** (provider endpoints only)
- **AC-15** — Given a latency range [min, max] for a provider (default 100–5000 ms), then each request waits a
  uniformly random time in that range before the first byte. Requests are served concurrently: three providers
  requested in parallel answer in about the slowest latency, not the sum.
- **AC-16** — Given an `error-rate` p for a provider (default 0.10), then each request fails with `500` Problem
  Details with probability p, after the latency; p = 0 never fails, p = 1 always fails. A successful response's
  content never depends on chaos.
- **AC-17** — When `POST /admin/providers/{id}/outage` is called (`204`), then that provider answers `503` Problem
  Details immediately, without latency, until `DELETE /admin/providers/{id}/outage` (`204`). Other providers are
  unaffected. `GET /admin/providers` lists each provider's latency range, error rate and outage state.
- **AC-18 (error)** — An unknown provider id on the admin API ⇒ `404` Problem Details.
- **AC-19** — Given the `deterministic` profile, then latency and error rate are zero for every provider and the
  seed is fixed; the outage switch still works.

**Expected figures**
- **AC-20** — `GET /admin/world/stats[?date=]` returns, for the current settings and reference date (and partition
  day if given): distinct itineraries; invalid itineraries per kind; per provider the records served, malformed
  records per kind, same-provider duplicates and itineraries whose records from that provider are all malformed;
  cross-provider duplicates; itineraries whose records are all malformed (total and per invalid kind, since a
  client can't classify them); and the **expected aggregation** that
  spec 001 should produce from the well-formed records: offers received per provider, discarded per reason,
  duplicates merged and itineraries produced, with received = discarded + merged + produced. Each figure equals the
  count obtained by parsing the three provider responses.

**Operations**
- **AC-21** — `GET /actuator/health` returns `UP` and is never delayed, failed or affected by outages.
- **AC-22** — `docker compose up --build` starts `gds-mock` on port 8081 and its healthcheck reports `healthy`, on
  amd64 and arm64.

## API contract

Provider ids: `mock-alpha`, `mock-beta`, `mock-gamma` (the ids spec 001 uses). Port 8081.

`GET /{providerId}/flights[?date=2026-11-10]` — examples below show one direct itinerary (IB345 MAD→BCN, departing
2026-11-10 08:00Z) offered by all three providers.

Mock-Alpha `200 application/json`:
```json
[
  {"offerRef":"ALP-20261110-000017-0","cxr":"IB","totFare":129.90,"cur":"EUR",
   "legs":[{"fltNum":"IB345","org":"MAD","dst":"BCN","depTs":1794297600000,"arrTs":1794302700000}]}
]
```

Mock-Beta `200 application/xml`:
```xml
<response provider="mock-beta">
  <itineraries>
    <itinerary id="BET-20261110-000017-0">
      <pricing><totalAmount currency="EUR">131.40</totalAmount></pricing>
      <originDestinationOptions>
        <option>
          <segments>
            <segment>
              <carrier>IB</carrier>
              <flightNumber>345</flightNumber>
              <departure airport="MAD" dateTime="2026-11-10T09:00:00+01:00"/>
              <arrival airport="BCN" dateTime="2026-11-10T10:25:00+01:00"/>
            </segment>
          </segments>
        </option>
      </originDestinationOptions>
    </itinerary>
  </itineraries>
</response>
```

Mock-Gamma `200 text/csv`:
```text
REF,PRICE_EUR,SEGMENTS
GAM-20261110-000017-0,127.15,IB0345~MAD~2026-11-10T09:00~Europe/Madrid~BCN~2026-11-10T10:25~Europe/Madrid
```
Several segments are joined with `|`.

Unparseable times: Alpha `"depTs":"TBD"`, Beta `dateTime="2026-11-10T25:61:00+01:00"`, Gamma `10/11/2026 9h`.

`GET /admin/providers` `200`:
```json
[{"id":"mock-alpha","latencyMinMs":100,"latencyMaxMs":5000,"errorRate":0.10,"outage":false}]
```
`POST|DELETE /admin/providers/{id}/outage` → `204`.

`GET /admin/world/stats[?date=]` `200`:
```json
{
  "referenceDate": "2026-11-05", "horizon": {"from": "2026-11-06", "to": "2026-11-12"}, "date": null,
  "distinctItineraries": 100000,
  "invalidItineraries": {"MIXED_AIRLINES": 714, "...": 0},
  "crossProviderDuplicates": 85000,
  "providers": {
    "mock-alpha": {"records": 61800, "sameProviderDuplicates": 300, "fullyMalformedItineraries": 380,
                   "malformed": {"BAD_AIRPORT_CODE": 205, "...": 0}}
  },
  "fullyMalformedItineraries": {"total": 410, "byInvalidKind": {"MIXED_AIRLINES": 3, "...": 0}},
  "expectedAggregation": {
    "offersReceived": {"mock-alpha": 61182}, "discarded": {"MIXED_AIRLINES": 1420},
    "duplicatesMerged": 84000, "itinerariesProduced": 94900
  }
}
```
(Numbers illustrative.)

Errors: `400` (bad `date`), `404` (unknown provider), `500` (injected error), `503` (forced outage). Body: RFC 9457
Problem Details.

## Domain model impact

New module, base package `com.andresps.gdsmock`; nothing shared with `flight-aggregator`. Plain-Java generator:
airports (IATA code + IANA zone), airlines, scheduled segments, world itineraries (partition day, segments, base
fare, invalid kind, offering providers), provider records (record id, price, malformation). Settings are a validated
record (AC-3).

## Non-functional requirements

- **Tests:** every random behaviour is seeded in tests (no flaky statistics). Tests over 1,000,000 records are tagged
  `slow` and excluded from the default build (`./mvnw verify -Pslow-tests` runs them).
- **Memory:** generation and serialization are lazy; memory does not grow with the volume (AC-12). Stats are one
  lazy pass with counters, cached per reference date and partition.
- **Determinism:** data depends only on seed, reference date and settings, never on request order or chaos.
- **Separation:** the generator has no framework dependency; one serializer per provider behind one interface; chaos
  applied outside serializers and controllers. Enforced by an `ArchitectureTest` in the module, which also forbids
  any dependency on `com.andresps.flights`.
- **Security:** the admin API is unauthenticated by design (a mock on an internal network).

## Consequences for the ingestion spec

- Per-day partitioning means about 7 requests per provider per ingestion run, **each** subject to latency and the
  error rate (at 10 %, a provider fails at least one of 7 partitions ~52 % of the time). Ingestion must time out and
  retry **per partition** and accept partial results, recording which partitions failed.
- Adapters must normalise times to `Instant` (epoch ms, offset date-times, local date-time + zone) and flight
  numbers (no leading zeros, carrier + number joined), or deduplication across providers fails.
- Malformed records must be counted per provider, never sent to the aggregator. Any field a client can't parse counts as malformed, including fields the mock
  never corrupts today (e.g. a non-numeric flight number): adapters must not let a parse exception abort a feed.
- `GET /admin/world/stats` is the oracle to reconcile an ingestion report. `ALREADY_DEPARTED` counts hold while the
  aggregator's clock is on or after the reference date and before the horizon starts.

## Open questions

All resolved (2026-11-05):

- [x] Q1 `volume` = distinct itineraries over the whole horizon — yes.
- [x] Q2 Horizon starts the day after the reference date — yes.
- [x] Q3 `/{providerId}/flights` with an optional `date` filter — yes.
- [x] Q4 Forced outage ⇒ immediate `503`; random errors ⇒ `500` after the latency — yes.
- [x] Q5 Defaults: invalid 5 %, malformed 1 %, error rate 10 %, providers per itinerary 40/35/25 % — yes.
- [x] Q6 Flight-number formats differ per provider on purpose (Beta split, Gamma zero-padded) — yes.
- [x] Q7 Same-provider duplicates in scope (0.5 % default); mid-stream truncation and gzip out — author's decision.
- [x] Q8 Unauthenticated admin API that only toggles the outage — yes.
- [x] Added by the author: `GET /admin/world/stats` (AC-20), the partition day of `ALREADY_DEPARTED` (AC-6), the
  consequences section above, multi-arch image (AC-22).
- [x] Added by the author at the test checkpoint: fully malformed itineraries in the stats (AC-20, exact
  reconciliation), concurrent serving (AC-15), quiet client aborts (AC-12), seeded tests and a `slow` tag.
