# 003 — Core domain: validation, deduplication and markup (spec 001)

- **Date:** 2026-11-05
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `feat/001-itinerary-aggregation`

## Prompts

### 1. Kick-off

````text
We continue the assessment. Read CLAUDE.md, docs/exercise.md, SOLUTION.md, docs/adr/ and docs/ai/
before planning. Follow the same conventions as PR 1: everything in English, SDD workflow,
record this session in docs/ai/ (this prompt, a short summary of what was done, which
skills/agents were used and any notable correction you had to make), and update SOLUTION.md
where relevant.

Goal of PR 2: the core domain — itinerary model, validation of invalid connections/dead legs,
deduplication (cheapest offer wins) and OTA markup. Pure domain only: no ingestion, HTTP,
persistence, Spring or provider formats yet. This is the part reviewers will look at most,
so favour clarity, small cohesive types and thorough tests over breadth.

Steps:
1. Run /spec-feature to write specs/001-<slug>/spec.md from the statement and ADR-0006+
   domain assumptions. Acceptance criteria must cover at least:
   - identity of an itinerary: same airline, flight numbers, connections (segment airports)
     and departure times => same itinerary, regardless of provider;
   - among duplicates, the cheapest base price wins; deterministic tie-break (document it);
   - 5% default markup applied to the selected base price (configurable), BigDecimal,
     EUR, scale 2, HALF_EVEN;
   - invalid itineraries are discarded with a reason: segments that don't chain,
     connection < 45 min, layover > 24 h, arrival not after departure, already departed
     (relative to an injected java.time.Clock), mixed airlines;
   - duplicates coming from the same provider are handled too;
   - the aggregation reports statistics: offers received per provider, discarded per reason,
     duplicates merged, itineraries produced.
   Show me the spec and its open questions and wait for my approval before coding.

2. Design guidance (propose and justify in the plan; record significant choices with /adr):
   - Value objects with invariants: e.g. AirportCode (IATA, 3 letters), AirlineCode,
     FlightNumber, Money, ProviderId, Segment, Itinerary, ItineraryKey (deterministic,
     derived from the identity fields), FlightOffer (itinerary + provider + base price),
     AggregatedItinerary (selected offer, base price, final price, providers that offered it).
   - Validation as a set of small rules behind one interface (open/closed), each returning
     a discard reason, composed by a validator.
   - Markup behind a MarkupPolicy interface with a percentage implementation.
   - Deduplication as an incremental accumulator (accept offers one by one, then produce
     results) so it can later be fed from streaming parsers and partitioned for 1M offers;
     note the memory trade-off for SOLUTION.md.
   - Keep everything framework-free (ArchUnit enforces it); use the package layout from CLAUDE.md.
   - SOLID must be visible in the design, pragmatically: in the plan, state for each
     principle (SRP, OCP, LSP, ISP, DIP) where and how it applies in this domain
     (e.g. one rule per class, new rules/markup policies added without modifying existing
     code, rules interchangeable behind their interface). Don't add interfaces or layers
     that have a single obvious implementation and no variation point — avoid
     over-engineering. Summarize this mapping in the PR description and in SOLUTION.md.

3. After I approve the spec, run /implement-spec 001 on a feat/001-<slug> branch:
   tests first from the acceptance criteria (@DisplayName("AC-n: ...")), parameterized tests
   for rule variations, then the implementation.
   - This is the first functional PR: remove the template's example `greeting` feature
     (code, tests, spec 000, usage docs), but keep a minimal integration test that boots the
     application context and checks /actuator/health so Failsafe/JaCoCo stay meaningful.

4. Run the code-reviewer and spec-reviewer agents, fix their findings, and record the main
   findings in docs/ai/.

5. ./mvnw verify must be green. Then prepare the PR with /pr-description.
````

### 2. Spec approval with changes

````text
Spec looks good. Approve it with these changes:
1. Package naming: avoid `aggregation.domain.aggregation`; rename the sub-package to
   `deduplication` (or the feature to `itinerary`), your call, keep it consistent.
2. Make the rule evaluation order explicit in the spec, since only the first failure counts:
   structural first (mixed airlines, arrival not after departure, segments don't chain,
   revisited airport), then timing (min connection, max layover), then already departed.
3. Add invalid-connection cases:
   - a segment whose origin equals its destination is malformed (Segment invariant);
   - an itinerary that visits the same airport twice (e.g. MAD-BCN-MAD-LHR) is discarded
     with a new reason REVISITED_AIRPORT.
4. Open questions:
   Q1 yes. Q2 yes: arrival time as tie-break, plus a final tie-break on the canonical
   itinerary representation so results never depend on input order. Q3 no: a base price
   must be > 0 (FlightOffer invariant, malformed input), document it as an assumption.
   Q4 yes. Q5 yes, and note in the spec that search must also filter out departed flights
   at query time (later PR). Q6 round, and document that prices differing only beyond the
   2nd decimal tie and fall to the tie-break. Q7 yes.
5. Note in the spec that malformed records never reach the aggregator, so the ingestion
   report (later PR) must count them per provider to keep the numbers reconcilable.

Then mark the spec Approved and continue with /implement-spec.
After writing the tests (before implementing), stop and show me the list of tests grouped
by acceptance criterion so I can check them.
````

### 3. Test review checkpoint

```text
The test list looks good, continue with the domain implementation
```

## Clarifications (asked by the agent while writing the spec)

The agent's proposed answer is marked in bold; the author's decision follows.

| # | Question | Decision |
|---|---|---|
| Q1 | Segment times as `Instant`, with adapters converting local times? | Yes |
| Q2 | Same provider + same price but different arrival: first accepted, or arrival tie-break? (**arrival**) | Arrival, then the canonical representation |
| Q3 | Is a 0.00 base price valid? (**valid**) | No: price > 0 is a `FlightOffer` invariant (author overrode the proposal) |
| Q4 | Only the first failing rule counts, so counts add up? | Yes, with an explicit order chosen by the author |
| Q5 | "Already departed" = first departure not strictly after now? | Yes; search must also filter at query time |
| Q6 | Round prices with more than 2 decimals (`HALF_EVEN`) or reject? | Round; sub-cent differences tie |
| Q7 | No guaranteed output order? | Yes |

The author also added the `REVISITED_AIRPORT` rule and the origin ≠ destination invariant. The agent chose to keep
`aggregation` as the feature name with a `deduplication` sub-package, because a later search feature will also deal
with itineraries, so `itinerary` would be ambiguous as a feature name.

## Skills, agents and hooks involved

- Skills: `spec-feature` (spec 001), `adr` (ADR-0011), `implement-spec` (branch, tests first, implementation),
  `pr-description`.
- Agents: `code-reviewer` and `spec-reviewer`, run in parallel on the branch.
- Hooks: `format-java.sh` (Spotless after each edit), `verify-on-stop.sh`, `guard-git.sh`.

## What was done

- Spec 001 with 24 acceptance criteria, plus ADR-0011 (incremental deduplication, key format, total-order tie-break,
  memory trade-off).
- Tests written first and reviewed by the author before any implementation (grouped by AC): parameterized rule
  tests at exact boundaries, every acceptance order of the duplicates (all permutations), a statistics
  reconciliation check (received = discarded + merged + produced).
- Framework-free domain in `aggregation.domain.{model, validation, pricing, deduplication}`.
- Greeting example removed; `ApplicationHealthIT` and a `GlobalExceptionHandlerTest` keep the IT phase and the shared
  handler covered.
- `SOLUTION.md`: domain design, SOLID mapping (including what is deliberately not abstracted), memory trade-off,
  new limitations. ADR-0010 annotated as refined by spec 001.

## Review findings and how they were handled

**spec-reviewer** (verdict: *Spec satisfied*): all 24 ACs traced to code and tests. Doc fixes applied:
- AC-24 said all identifiers are upper-cased, but provider ids keep their case. The spec wording was fixed.
- ADR-0010 lagged behind spec 001 (equal arrival/departure, `MIXED_AIRLINES`, `REVISITED_AIRPORT`, evaluation
  order). A "refined by spec 001" note was added, without rewriting the accepted ADR.
- The canonical-form tie-break test was labelled AC-7 although it can only decide across different keys. It was
  relabelled as a total-order test.

**code-reviewer** (verdict: *Needs changes*, nothing blocking). Fixed:
- **Clock read per offer.** With a real clock, an itinerary departing mid-run could be valid for early duplicates
  and `ALREADY_DEPARTED` for late ones, so the result depended on input order. `NotDepartedRule` now reads "now" once
  at construction. A test with an advancing clock covers it, and the spec states it in AC-19.
- **Unbounded `BigDecimal`.** `Money.eur("1E999999999")` would trigger huge `setScale` arithmetic instead of a domain
  error. Amounts are now bounded (fewer than 10 integer digits, at most 10 significant decimals), tested with a
  timeout.
- **Memory estimate about 3–5× too low.** ADR-0011 and `SOLUTION.md` now say ~1–1.5 GB at 1M distinct itineraries
  (unmeasured), and list mitigations to measure in the ingestion spec.
- **Determinism tested only on selection.** Added a test that shuffles the full mixed data set 50 times and
  compares the whole result, plus a comparator property test (antisymmetry, transitivity, 0 only for equal offers).
- **Smaller items:** `result()` snapshot test, unmodifiable derived lists in `Itinerary`, negative rule thresholds
  rejected, sub-minute boundary cases (44m59s, 24h00m01s), a null-check message on the provider set, and Javadoc
  explaining why the last tie-break steps exist.

**Not changed (left for the author):** flight numbers with leading zeros (`IB0123` vs `IB123`) produce different keys.
That is a business decision, so it is recorded as an assumption and limitation and raised in the PR. Statistics
`plus()` and key caching were left out (YAGNI until partitioning exists).

## Notable corrections during the session

- **`List.of(...).contains(null)` throws `NullPointerException`** (immutable lists reject null queries). The first
  implementation of the itinerary's null-segment check failed most tests. It was replaced by
  `stream().anyMatch(Objects::isNull)`.
- **The Stop hook conflicts with a test-first checkpoint.** The author asked the agent to stop after writing tests
  and before implementing. At that point the build cannot compile, so `verify-on-stop.sh` reported a failure and
  asked for a fix. The agent kept to the author's checkpoint, explained that the red build was expected, and did not
  work around the hook. Possible follow-up (`chore/`): let the hook recognise an intentional test-first pause, e.g.
  a marker file managed by the `implement-spec` skill.
- **A scripted multi-file edit failed partway** because Spotless (run by the post-edit hook) had reformatted a file
  the script expected verbatim. The agent re-read the file and applied the remaining edits. Edits that the script
  had already applied were kept.
