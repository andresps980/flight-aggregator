# Spec 008: Per-itinerary provider currencies, normalised to EUR with live exchange rates

- **Status:** Implemented
- **Branch:** feat/008-different-currency
- **Related ADRs:** [ADR-0025](../../docs/adr/0025-currency-normalisation-live-rates.md) (currency normalisation with
  live rates), [ADR-0010](../../docs/adr/0010-domain-assumptions.md) (item 3 amended),
  [ADR-0013](../../docs/adr/0013-stateless-seeded-streamed-generation.md) (seeded generation),
  [ADR-0017](../../docs/adr/0017-day-snapshot-replacement.md) (stale days)

## Context

Requester: *"Now we want that the providers can send in different currency so the normalize process has to work with
it and convert to euro."* Refined on review: *"No properties in the flight-aggregator, the currency comes from the
provider [in the feed]; a live FX API is in the scope"* and *"each provider can serve a different currency for each
itinerary, chosen randomly using the same seed, as usual."*

Today every price must be EUR (ADR-0010 item 3): a record whose `cur` / `currency` is not `EUR` is malformed and
Gamma's header is `REF,PRICE_EUR,SEGMENTS`. From now on **every itinerary record declares its own currency**, the mock
picks it per record from the provider's configured currencies with the world seed, and the aggregator converts every
price to EUR with the run's live exchange rates before deduplication. Cheapest selection, markup, read model, search
API and dashboard stay in EUR.

## Scope

**In scope**
- **Feed formats** (`docs/providers.md`): Alpha `cur` and Beta `currency` per record (unchanged shape, any ISO 4217
  code); Gamma gains a per-line currency column: header `REF,PRICE,CURRENCY,SEGMENTS`, e.g.
  `GAM-20261106-000001-0,512.37,USD,VY0001~DUB~…`. The old `PRICE_EUR` header is no longer produced or accepted.
- **gds-mock**: `gds.providers.<id>.currencies` (ISO codes, default `[EUR]`) and `gds.fx.rates.<code>` (units per EUR).
  Each record's currency is drawn uniformly from its provider's list with a random derived from the world seed and the
  record (day, index, provider, copy), **separate from the generation stream**, so the world (itineraries, providers,
  EUR prices, duplicates, malformations, stats) is unchanged; the record's price is its EUR price × rate.
- **gds-mock FX double**: Frankfurter-compatible `GET /fx/latest?base=EUR` serving `gds.fx.rates`, for deterministic
  tests, CI and offline use.
- **Aggregator**: no per-provider currency property. Live rates from [Frankfurter](https://frankfurter.dev) (ECB
  reference rates, no key), `GET {base-url}/latest?base=EUR`, fetched **once per ingestion run**; each record converted
  to EUR in the parser before the `FlightOffer` is built; `Money` stays EUR-only.
- **FX failure** handled with the existing resilience: partitions containing a non-EUR price fail, the day keeps its
  previous snapshot (ADR-0017); EUR prices never depend on the FX API.
- **Default `docker compose up`**: every mock provider prices in `[EUR, USD, GBP]`; the aggregator uses the live
  Frankfurter API. The CI full-stack smoke test points the aggregator at gds-mock's `/fx/latest`.
- Docs: ADR-0025, ADR-0010 amendment, `docs/providers.md`, `docs/usage.md`, `SOLUTION.md`, architecture diagrams,
  OpenAPI (new failure kind), regenerated frontend types, re-recorded Gamma test fixtures.

**Out of scope**
- Keeping or showing the original currency / amount (read model, search API and dashboard stay EUR only).
- Weighted currency mixes, currency-related malformations in the mock, historical rates (sale-date rate), caching rates
  across runs, a fallback FX provider, searching or displaying in another currency.

## Acceptance criteria

**Currency per record in the feed (aggregator parsers)**
- **AC-1** — Given rates `USD = 1.25`, `GBP = 0.80` per EUR, when an Alpha record `"totFare":100.00,"cur":"USD"`, a
  Beta `<totalAmount currency="USD">100.00</totalAmount>` or a Gamma line `…,100.00,GBP,…` is parsed, then the base
  price is `80.00 EUR` (Alpha, Beta) and `125.00 EUR` (Gamma).
- **AC-2** — Given one feed mixing records in `EUR`, `USD` and `GBP`, then each record is converted with its own
  currency's rate, in every format.
- **AC-3** — A record in `EUR` is used as is, whether or not rates are available.
- **AC-4** — The EUR amount is the exact quotient `amount / rate` rounded once to 2 decimals `HALF_EVEN`
  (`10.00 / 1.6 = 6.25`; `0.05 / 2 = 0.025 → 0.02`; `0.15 / 2 = 0.075 → 0.08`; `100 / 3 = 33.33`).
- **AC-5** — Given the same itinerary offered by Alpha at `100.00 USD` (`80.00 EUR`) and by Beta at `85.00 EUR`, when
  the day is aggregated, then Alpha is selected and the final price is `84.00 EUR` (5 % markup).

**Malformed data** (one reason per record, the first failing field in document order, as spec 003 AC-6)
- **AC-6** — A record whose currency is missing, blank, not an ISO 4217 code (`usd`, `XYZ`, `EURO`) or a valid code the
  rates don't include (`XAF`) is skipped as `INVALID_RECORD`; the following records are still parsed.
- **AC-7 (error)** — A Gamma feed whose header is not exactly `REF,PRICE,CURRENCY,SEGMENTS` (including the old
  `REF,PRICE_EUR,SEGMENTS`) fails as `MALFORMED_DOCUMENT`; a line without 4 fields is `INVALID_RECORD`.
- **AC-8** — A non-positive price is `NON_POSITIVE_PRICE` whatever the currency. The `Money` bounds (below
  1,000,000,000, at most 10 decimals) apply to the provider amount and to the EUR result; outside them the record is
  `INVALID_RECORD`.

**Live exchange rates (aggregator)**
- **AC-9** — When an ingestion run starts, then the rates are requested once (`GET {base-url}/latest?base=EUR`) and
  every partition of that run converts with that same response, whatever the number of days and providers.
- **AC-10 (error)** — Given the FX API times out, refuses the connection, answers non-2xx (5xx retried, 4xx not), or a
  body that is not JSON, has `amount` ≠ 1, a `base` other than `EUR`, no `rates` object, an unparseable `date` or a
  rate that is not a positive number, then the run continues **without rates**: EUR records are ingested normally, and
  a partition containing a non-EUR price fails with kind `EXCHANGE_RATES_UNAVAILABLE` after one attempt, not counted by
  the provider's circuit breaker; the day keeps its previous snapshot within `max-stale-age` (ADR-0017).
- **AC-11** — Rate entries whose code is not ISO 4217 are ignored; `EUR` always converts at 1 even if absent.

**Mock providers (gds-mock)**
- **AC-12** — Given `gds.providers.<id>.currencies: [EUR, USD, GBP]` and rates, when a feed is requested, then every
  record declares one of those currencies, all three appear in a feed of a few hundred records (each 33 % ± 10 pp at
  1,400 records), the price is the record's EUR price × rate rounded to 2 decimals `HALF_EVEN` (a non-positive
  malformed price stays non-positive), and the same seed always yields the same currency for the same record.
- **AC-13** — The currency draw does not change the world: with any currency configuration, `/admin/world/stats` and
  every record's itinerary, provider, copy and malformation are identical to the `[EUR]` configuration; a provider
  configured `[EUR]` (the default) serves Alpha and Beta byte for byte as before.
- **AC-14 (error)** — A currency that is not ISO 4217, a non-EUR currency without a rate, an empty list, an unknown
  provider id, or a rate that is not positive stops gds-mock at startup with a message naming the property.
- **AC-15** — `GET /fx/latest?base=EUR` (base optional) answers `200` with
  `{"amount":1.0,"base":"EUR","date":"<reference date>","rates":{…}}` from `gds.fx.rates`; another `base` ⇒ `400`
  Problem Details. It is never subject to chaos (latency, errors, outages).

**End to end**
- **AC-16** — Given gds-mock with mixed currencies and the aggregator using gds-mock's FX endpoint, when an ingestion
  runs end to end, then the run is `COMPLETED`, the record / malformed / published counts reconcile with gds-mock's
  stats as before (spec 003 AC-22), and the full-stack smoke test passes.

## API contract

`/api/v1` search responses unchanged (`basePrice`, `finalPrice` in EUR, `"currency": "EUR"`). The ingestion run API
gains one failure kind, `EXCHANGE_RATES_UNAVAILABLE` (OpenAPI enum, regenerated frontend types).

FX API consumed (Frankfurter, and gds-mock's double) — a rate is units of that currency per 1 EUR, so
`EUR = amount / rate`:

`GET {base-url}/latest?base=EUR` → `200`
```json
{"amount":1.0,"base":"EUR","date":"2026-11-09","rates":{"GBP":0.8343,"USD":1.1193}}
```

Feeds: Alpha `"cur":"USD"`, Beta `<totalAmount currency="USD">`, Gamma `REF,PRICE,CURRENCY,SEGMENTS` with
`GAM-…,512.37,USD,<segments>`.

## Domain model impact

- **Aggregator.** `Money` unchanged (EUR, scale 2; its magnitude check becomes reusable). New value object
  `ExchangeRates` (aggregation domain): rates per currency, EUR = 1 implicit, `Money toEur(BigDecimal, Currency)`; an
  unknown currency is `InvalidFlightDataException`; `ExchangeRates.unavailable()` converts EUR only and signals other
  currencies with `ExchangeRatesUnavailableException`. New driven port `ExchangeRateSource` (`ExchangeRates latest()`)
  with a Frankfurter HTTP adapter. `ProviderFeed.fetch(day, rates)` and `FeedParser.parse(body, rates, buffer)` receive
  the run's rates. New `FailureKind.EXCHANGE_RATES_UNAVAILABLE`.
- **gds-mock.** `ProviderRecord` gains `currency`; its `price` is in that currency. New `FeedCurrency` (code + units per
  EUR) and a per-provider currency list in the generator settings; the draw lives in the generator, the formats only
  write what the record says.

## Non-functional requirements

- One FX request per run (plus retries), short timeouts (connect 1 s, read 5 s, 3 attempts): a slow FX API delays a run
  by seconds, never blocks it.
- One `BigDecimal` division per non-EUR record; no rate lookup for EUR records. The mock's extra draw is one small
  `SplittableRandom` per record only when the provider has more than one currency.
- The only new aggregator setting is the FX API location, `aggregator.exchange-rates.base-url` (default
  `https://api.frankfurter.dev/v1`); rates and their date are logged once per run.

## Open questions (proposed answers)

- [ ] **Gamma format.** Proposed: a 4th column, `REF,PRICE,CURRENCY,SEGMENTS`, replacing `PRICE_EUR` (no dual support:
      gds-mock is the only Gamma producer). The recorded Gamma fixtures of the aggregator tests are re-recorded.
- [ ] **How the mock picks the currency.** Proposed: uniform over `gds.providers.<id>.currencies`, from a random seeded
      by (world seed, day, index, provider, copy), independent of the generation stream, so the world and its stats
      don't change (AC-13). A duplicate copy of a record may get a different currency.
- [ ] **Default currencies.** Proposed: `[EUR]` in `application.yml` (tests and recordings stay stable), and
      `[EUR, USD, GBP]` for every provider in `docker-compose.yml` and the CI smoke override.
- [ ] **Mock rates.** Proposed defaults in gds-mock's `application.yml`: `USD: 1.10`, `GBP: 0.85` (close to today's ECB
      rates); served by `/fx/latest` with the world's reference date.
- [ ] **FX failure.** Proposed as in AC-10: per-partition `EXCHANGE_RATES_UNAVAILABLE`, stale-day policy. With mixed
      currencies almost every partition holds a non-EUR price, so an FX outage behaves like a provider outage for the
      run; previous snapshots keep serving search for up to `max-stale-age` (2 h).
- [ ] **FX base URL.** The single new aggregator setting (needed to target gds-mock in tests/CI and offline).
- [ ] **Unknown currency.** `INVALID_RECORD`, no new malformed reason, so counts keep reconciling with gds-mock.
