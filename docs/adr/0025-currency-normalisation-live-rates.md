# ADR-0025: Per-record provider currencies, normalised to EUR at the ingestion boundary with live rates fetched once per run

- **Status:** Accepted
- **Date:** 2026-11-10
- **Related spec:** [specs/008-different-currency](../../specs/008-different-currency/spec.md)
- **Amends:** [ADR-0010](0010-domain-assumptions.md) item 3 (single currency: EUR)

## Context

Providers may now price **each itinerary** in a different currency, declared on the record itself (Alpha `cur`, Beta
`currency`, a new Gamma `CURRENCY` column); the aggregator has no per-provider currency setting. Deduplication picks
the **cheapest** offer, so prices must be comparable, and the read model, search filters and dashboard are EUR. Rates
must come from a live FX API, which, like a provider, can be slow or down; up to 1,000,000 records a day must not each
trigger an FX call; the domain must stay framework-free (ADR-0002). In gds-mock, the currency must be random but
reproducible from the world seed (ADR-0013), without changing the world that tests and reconciliation rely on.

## Decision

We will:

1. **Convert at the ingestion boundary.** The parsers (the anti-corruption layer) turn each record's
   `amount + currency` into EUR `Money` before building the `FlightOffer`. `Money` stays EUR-only, so dedup, markup,
   persistence and the API are untouched.
2. **Model rates as a domain value object**, `ExchangeRates` (units per EUR, EUR = 1 implicit), with
   `toEur(amount, currency) = amount / rate`, the exact quotient rounded **once** to 2 decimals `HALF_EVEN`. An unknown
   currency is invalid data (`INVALID_RECORD`, as a non-EUR price was before).
3. **Fetch the rates once per run** through a driven port `ExchangeRateSource`, implemented by an HTTP adapter for
   [Frankfurter](https://frankfurter.dev) (ECB reference rates, free, no key), with short timeouts and retries. The use
   case passes that snapshot to every partition (`ProviderFeed.fetch(day, rates)`): consistent conversion within a run
   and one FX request whatever the volume.
4. **Degrade like a provider outage.** If the FX API fails, the run continues with `ExchangeRates.unavailable()`: EUR
   prices still convert; a partition that meets a non-EUR price fails as `EXCHANGE_RATES_UNAVAILABLE` (not retried, not
   counted by the provider's breaker: not the provider's fault), and the day keeps its previous snapshot (ADR-0017).
5. **Draw the mock's currency from a separate seeded stream.** gds-mock picks each record's currency uniformly from
   `gds.providers.<id>.currencies` with a `SplittableRandom` seeded by (world seed, day, index, provider, copy), not
   from the itinerary's generation stream, so itineraries, EUR prices, duplicates, malformations and stats are
   identical under any currency configuration. The record's price is its EUR price × the mock's rate; the mock serves
   the same rates as a Frankfurter-compatible `GET /fx/latest` for tests, CI and offline runs.

## Alternatives considered

1. **Static rates or a per-provider currency in the aggregator's configuration** — stale by design, duplicates (and
   can contradict) what the feed says; rejected by the requester.
2. **Convert in the domain (multi-currency `Money`, convert when comparing)** — every comparison, the markup and the
   read model would need rates; one mistake mixes currencies. Rejected: one conversion at the edge keeps the core EUR.
3. **Call the FX API per record or per partition, or cache rates across runs** — per record is up to 1M calls; per
   partition gives one run several snapshots; a cross-run cache adds state and a staleness policy for little gain at one
   request per 15 min. Rejected.
4. **On FX failure, drop non-EUR records or fail the whole run** — dropping publishes "complete" days missing most
   offers, replacing better snapshots; failing the run also loses EUR offers. Rejected for the partition-level failure,
   which reuses the stale-day policy.
5. **Draw the currency from the itinerary's generation stream** — simpler, but every later draw shifts: prices,
   duplicates and malformations of the world change with the currency list, breaking recorded fixtures and the
   "same world" guarantee. Rejected.
6. **Gamma: keep one currency per feed in the header (`PRICE_<code>`)** — cannot express per-itinerary currencies.
   Rejected for a `CURRENCY` column.

## Consequences

- **Positive:** domain, dedup, persistence, search and dashboard unchanged; a new currency needs no code (only the
  mock's list, and the FX API listing it); FX failures are visible per partition in the ingestion report and never
  corrupt data; the mock's world and its reconciliation figures are independent of currencies.
- **Negative / trade-offs:** prices use the latest ECB reference rates (published once per working day), not the
  provider's sale-time rate; the original amount and currency are not stored; currencies outside the ~30 ECB ones are
  `INVALID_RECORD`; with mixed currencies an FX outage fails almost every partition, so stale snapshots carry search
  until the FX API recovers or `max-stale-age` expires; the stack depends on an external service (gds-mock's double in
  CI). `ProviderFeed` and `FeedParser` gained a parameter; the Gamma format changed (fixtures re-recorded).
- **Follow-ups:** a fallback rates source or a last-known-rates cache if FX outages prove frequent; store the original
  amount if the business needs to show it.
