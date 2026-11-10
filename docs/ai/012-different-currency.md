# 012 — Provider currencies normalised to EUR with live exchange rates (spec 008)

- **Date:** 2026-11-10
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `feat/008-different-currency` (PR 16)
- **Skills / agents / hooks:** `spec-feature`, `adr`, `implement-spec`, `pr-description` skills; `code-reviewer` and
  `spec-reviewer` agents; hooks: Spotless / Prettier after edits, verify on stop, pre-commit / pre-push gates.

## Prompts

### 1. Task

````text
We continue the flight-aggregator. Read CLAUDE.md, docs/exercise.md, SOLUTION.md,
docs/adr/, docs/ai/ and specs/ (especially <relevant specs and their follow-ups>) before
planning. Same conventions as previous PRs: English, SDD workflow, SOLID applied
pragmatically, record this session in docs/ai/ (prompt, summary, skills/agents used, review
findings, notable corrections), update SOLUTION.md where relevant, <merge strategy>; after
every push, wait for the CI run of the pushed commit and report it by SHA.
This is PR 16 in the repo.

Goal: Now we want that the providers can send in different currency so the normalize proccess
have to work with it anf convert to euro.

1. Run /spec-feature to write specs/008-different-currency.md. Cover at least:
   - The providers will have a new propertu to define a currency that it is in use
   - The ingest layer havde to normalize this currency to euro
   Show me the spec with open questions (with your proposed answers) and wait for my approval.

2. Design guidance (justify in the plan; /adr for significant choices):
   - SOLID principles without overenginnering

3. After I approve the spec, /implement-spec <NNN> on feat/provider-diffenret-currency. Tests first:
   - Create the unit test
   - Integration test
Show me the test with the AC to validate

4. Run code-reviewer and spec-reviewer, fix findings, record them in docs/ai/.
   Update <docs to update: architecture, usage, README, SOLUTION.md sections>.

5. Verify: ./mvnw verify <and other checks> green (report durations); <manual or compose
   checks, measurements to record>. Then /pr-description, push after my OK, wait for the CI
   run of the pushed commit and report it by SHA, and check code scanning findings.
````

### 2. First spec review

````text
go ahead but with this modificattions:
No properties in the flight-aggregator, the currency comes from the provider like PRICE_<CODE> and A live FX API is in the scope
````

Then, while the agent was working: *"Please take care of the adr too"*.

### 3. After the first set of tests: currency per itinerary

````text
Repeat from the  beginning, with this modifications:
- Each provider can serve different currency for each itinerary, thats means that shoulg be chose randomly using the same seed, as usual.
````

### 4. Approval of the rewritten spec

````text
Go ahead
````

While the agent was writing the tests: *"Remember to update SOLUTION.md, usage.md and all necessaries docs"*.

### 5. Tests shown against the acceptance criteria

````text
Yes, go ahead, but first, the test should be indicate the AC and no the spec
````

While the agent was implementing: *"dont forget record this session in doc/ai"*.

## Clarifications raised by the agent, and the answers

The first draft (one configured currency per provider, static rates in the aggregator's configuration) had seven open
questions with proposed answers. The author changed two decisions instead of answering them: **no aggregator
property** (the feed carries the currency) and **live rates in scope**. The second draft, after the author asked for a
currency per itinerary, asked again; all proposed answers were accepted with "Go ahead":

| Question | Answer (accepted) |
|---|---|
| Gamma can't declare a per-record currency in its header | New `CURRENCY` column: `REF,PRICE,CURRENCY,SEGMENTS`; `PRICE_EUR` dropped, no dual support; Gamma fixtures re-recorded |
| How the mock picks the currency | Uniform over `gds.providers.<id>.currencies`, from a random of its own seeded by (seed, day, index, provider, copy), so the world and its stats never change |
| Defaults | `[EUR]` in gds-mock's `application.yml` (tests and recordings stable); `[EUR, USD, GBP]` for every provider in compose and the CI smoke stack |
| Rates source | Frankfurter (ECB reference rates, no key), checked live before writing the spec: rates are units per EUR, so `EUR = amount / rate`; gds-mock serves a compatible `/fx/latest` |
| FX API down | Run continues; partitions with a non-EUR price fail as `EXCHANGE_RATES_UNAVAILABLE` (no retry, no breaker), the stale-day policy covers them |
| The one aggregator setting left | `aggregator.exchange-rates.base-url` (`FX_API_URL`), needed to target gds-mock in tests, CI and offline; flagged as the single exception to "no properties" |
| Unknown or invalid currency | `INVALID_RECORD`, no new malformed reason, so counts keep reconciling with gds-mock |
| Spec path and branch | The repo conventions (`specs/008-different-currency/spec.md`, `feat/008-different-currency`) instead of the names in the prompt |

## What was done

- **Spec 008** (16 acceptance criteria), **ADR-0025** (normalise at the ingestion boundary, one FX snapshot per run,
  degrade like a provider outage, seeded currency draw apart from the generation stream), **ADR-0010** item 3 amended.
- **Aggregator.** `ExchangeRates` value object (aggregation domain; exact quotient rounded once `HALF_EVEN`; unknown
  currency is invalid data, no rates is `ExchangeRatesUnavailableException`); `Money.requireBounded` reused for the
  provider amount; driven port `ExchangeRateSource` and `FrankfurterExchangeRateSource` adapter (Jackson tree, retries
  on 5xx / timeouts / I/O only, invalid bodies rejected, non-ISO entries skipped); `IngestionService` reads the rates
  once per run; `ProviderFeed.fetch(day, rates)` and `FeedParser.parse(body, rates, buffer)`; the parsers read amount
  then currency in document order (Alpha converts as soon as both fields are read, in either order);
  `HttpProviderFeed` maps missing rates to the new `FailureKind.EXCHANGE_RATES_UNAVAILABLE`, which is outside the
  retryable set, so it is neither retried nor recorded by the breaker. OpenAPI enum and frontend types regenerated;
  the dashboard renders kinds as text, so no UI change.
- **gds-mock.** `FeedCurrency`, `FxRates`, `ProviderCurrencies` (per-record draw with its own `SplittableRandom`),
  `ProviderRecord.currency`, formats writing each record's currency (Gamma's new column), `CurrencyProperties`
  (`gds.providers.<id>.currencies`, `gds.fx.rates.<code>`, validated at startup naming the property),
  `FxRatesController` (`GET /fx/latest`, outside the chaos interceptor).
- **Compose.** Every provider mixes EUR/USD/GBP (`SPRING_APPLICATION_JSON`, since environment variables can't spell the
  dashed provider ids); the aggregator uses the live API (`FX_API_URL`), the CI override uses gds-mock's double.
- **Docs.** `docs/providers.md`, `docs/usage.md`, architecture (domain model, ingestion application, provider/FX
  adapters, run flow, gds-mock inventory), `SOLUTION.md` (summary, decisions, per feature, data model, limitations,
  production table), README, spec and ADR indexes.

## Notable corrections during the session

- **The design changed twice before any production code.** Per-provider configured currency + static rates → feed
  currency + live rates → per-itinerary currency. The first attempt's spec, ADR and red tests were kept on a local
  backup branch and the feature branch restarted from `main`; most aggregator tests (rates, FX adapter, run-level
  rates, HTTP feed) carried over, the Gamma and mock tests were rewritten.
- **Test names.** Display names first read `Spec 008 AC-n`; the author asked for the AC alone (`AC-n: …`), as in the
  other specs.
- **The currency draw must not consume the generation stream.** Drawing from the itinerary's `SplittableRandom` would
  have shifted every later draw (prices, duplicates, malformations). Checked after implementing: re-recorded feeds of
  Alpha and Beta are byte-identical to the old fixtures and `/admin/world/stats` has the same content; only the 7 Gamma
  files changed (new column).
- **A `Map` bean is ambiguous in Spring** (it may collect beans by type): the mock's rates went into an `FxRates`
  record before the first run.
- **Mermaid:** a `;` inside a sequence message ends the statement; the render check caught it in `flows.md`.
- **Hand-written OpenAPI enum.** `FailureDocument.kind` lists its values in a `@Schema` annotation; the contract IT
  (`enumerationsMatchTheCode`) failed until the new kind was added there too.
- A trivial test ("the bad currencies tested are the spec's examples") was written and removed before committing.

## Code review (`code-reviewer` agent)

Verdict: *needs changes*, nothing blocking. It traced the exception path (parser → `HttpProviderFeed.classify` →
`EXCHANGE_RATES_UNAVAILABLE`, outside the retryable set), the rounding, the bounds and the layering, and measured two
attacks by hand. The author's standing instruction: fix findings, record them.

| # | Finding | Outcome |
|---|---|---|
| S1 | The FX fetch had no total deadline (a server dribbling a byte every 4 s, under the 5 s read timeout, would block the run, holding the run lock) and an unbounded body | **Fixed**: each attempt runs on a virtual thread with a 10 s deadline (as provider attempts do), body capped at 64 KiB; ITs with a dribbled body (3 attempts, < 15 s) and a 64 KiB+ body |
| S2 | An untrusted rate like `"USD":1e100000000` passes every check; one division by it ran > 60 s, once per USD record, and CPU-bound work ignores interrupts | **Fixed**: rates go through `Money.requireBounded` in `ExchangeRates.of`, so such a body is invalid (→ no rates); unit and IT cases for `1e±100000000` |
| S3 | GBP 0.85: about 15 % of EUR cents come back a cent off after ×rate, ÷rate; the mock's Javadoc claimed "to the cent" | **Fixed** Javadoc; **documented** in SOLUTION.md 3.6; checked that no test or script compares exact prices (the exact IT uses rate 2) |
| S4 | Beta checked the amount before the `currency` attribute (not document order, unlike Alpha); AC-8 wording then contradicted AC-6 | **Fixed** (also found by the spec reviewer): currency first; AC-8 reworded; a test pins the order for the three formats |
| n1 | A display name said PARTIAL where the test asserts FAILED | **Fixed** |
| n2 | `/fx/latest` returned a `Map` | **Fixed**: `FxRatesResponse` record |
| n3 | The FX adapter reuses the provider package's `RetrySettings` / `ResiliencePolicies.backoff` | **Accepted**: small, stable, avoids a duplicate |
| n4 | Hard-coded FX retry settings | **Fixed**: named constants with the worst case (≈ 3 × 10 s + 1.5 s) |
| n5 | The unavailability message carried `RestClientException.getMessage()` (part of a 5xx body) into a WARN log | **Fixed**: status or I/O cause only |
| n6 | Any exception other than the port's would abort the whole run before any partition | **Fixed**: caught, logged at ERROR, the run continues without rates (test added) |
| n7 | `HttpURLConnection` follows same-protocol redirects | **Accepted**: the base URL is configuration, validated as http(s); no user input reaches it |
| n8 | Inline `java.util.*` qualified names; `gds.fx.rates` keys differing only by case overwrote each other | **Fixed**: imports; a duplicate key stops the startup |
| n9 | The per-run rates log line was in hash order | **Fixed**: sorted |
| — | Its `./mvnw verify` overlapped another build on the same tree (`NoClassDefFound`, a 3.13 s vs 3 s deadline IT) | Not a code issue; re-run on a quiet machine: green (below) |

While re-running verify, the agent's change in the test config (FX URL on a closed port) showed a side effect:
`IngestionParallelismIT` took 3.8 s against its 3.5 s bound, because each run first spent ≈ 1.5 s retrying the dead FX
URL. **Fixed**: the two timing-sensitive ITs with their own WireMock stub the FX API too (the recorded-feeds base
already did); the closed-port default stays for tests that don't run an ingestion.

## Spec review (`spec-reviewer` agent)

Verdict: all 16 acceptance criteria implemented, no spec drift (timeouts, defaults, status codes, rates), no scope
creep of substance. Findings:

| # | Finding | Outcome |
|---|---|---|
| 1 | AC-14's startup cases sat in the spec 002 "AC-3" parameterised test | **Fixed**: own test "AC-14 (error): …", plus `gds.fx.rates.EUR=1.5` |
| 2 | No test name started with AC-16 | **Fixed**: `CurrencyIngestionIT` "AC-16/…", e2e "AC-22/AC-16" |
| 3 | AC-10's "day keeps its previous snapshot" had no FX-specific test (only Beta failed in `fxDown`) | **Fixed**: `fxDownKeepsPreviousSnapshots` — every provider mixed, FX down: run `FAILED`, every day still the first run's snapshot, marked stale, same rows |
| 4 | Beta check order vs document order | **Fixed** (code review S4) |
| 5 | An EUR entry ≠ 1 in the FX body rejected the whole answer; gds-mock dropped `gds.fx.rates.EUR=1.5` silently | **Fixed**: the aggregator ignores the EUR entry (AC-11: always 1); gds-mock refuses to start |
| 6 | "To the cent" Javadoc | **Fixed** (code review S3) |
| 7 | Open questions still unticked on an implemented spec | **Fixed** |
| 8 | docs/ai/012 not committed yet | **Fixed** with the review commit |
| 9 | `" USD "` accepted (surrounding blanks stripped) | **Accepted**: blank is rejected, as the spec asks |

## Verification

- `./mvnw verify` after the review fixes: **green in 79 s** — flight-aggregator 576 unit + 212 integration tests,
  gds-mock 167 unit + 53 integration tests (coverage gates, Spotless, ArchUnit, `ArchitectureDocsTest`). Before the
  review, the only failure had been the expected OpenAPI drift.
- `npm run check`: green in 9 s, 118 tests, 99.34 % lines / 95.18 % branches.
- `scripts/check-mermaid.sh`: 46 diagrams in 21 files render (also after the review's diagram updates).
- `scripts/stack-smoke.sh` with every provider mixing EUR/USD/GBP and the FX double: **PASSED, 15 checks in 49 s**
  (build 16 s, health wait 16 s); the e2e reconciliation (AC-16) and the Mock-Beta outage scenario included.
  After the review fixes: **PASSED, 15 checks in 39 s** (build 8 s, health wait 16 s).
- **Live FX (manual, CI stack + `FX_API_URL=https://api.frankfurter.dev/v1`):** first scheduled run `COMPLETED`,
  18,944 itineraries, converted with 29 ECB currencies dated 2026-11-10 (USD 1.1214, GBP 0.83545); a Mock-Beta day feed
  had 582 EUR / 598 USD / 606 GBP records.
- **FX down (manual, `FX_API_URL` pointing at a 404):** run `FAILED` (every provider mixes currencies), each provider 7
  partitions × 1 attempt, all `EXCHANGE_RATES_UNAVAILABLE`, breakers `CLOSED`; search kept serving the previous data,
  marked `STALE`. As ADR-0025 and SOLUTION.md section 3.4 describe.
