# ADR-0015: Atomic partitions through a per-attempt buffer

- **Status:** Accepted
- **Date:** 2026-11-05
- **Related spec:** [specs/003-provider-ingestion](../../specs/003-provider-ingestion/spec.md)

## Context

A partition (provider, day) is streamed and parsed record by record. An attempt can fail after part of the body was
read: a reset connection, a timeout, a truncated or broken document. Spec 001's `OfferAggregator` can't take offers
back, so feeding it record by record would leave half a partition in the result, and a successful retry would add
those offers a second time (counted as same-provider duplicates, skewing the statistics that must reconcile exactly
with gds-mock).

## Decision

We will make an attempt the unit of delivery:

- Each attempt parses into its **own buffer**: the list of parsed `FlightOffer`s and the malformed counts per reason.
- Only a completed attempt returns its buffer (`ProviderPartition`) to the use case; a failed attempt's buffer is
  dropped, and a retry starts with an empty one. The port contract is "a fully parsed partition or an exception".
- The day coordinator feeds a partition to the day's aggregator as soon as it arrives and drops it (ADR-0016), so a
  buffer lives only from its attempt's start until it has been fed.

Memory: a buffer holds one day partition of one provider. At 1,000,000 itineraries that is ≈ 88,000 offers,
estimated at 45–90 MB; at most `max-concurrent-partitions` per provider are in flight. This is measured in this PR.

## Alternatives considered

1. **Feed record by record and roll back on failure** — needs an undo operation in the domain aggregator (remove an
   offer, restore the previous winner), which a "keep the minimum" accumulator can't do without keeping every offer.
2. **Feed record by record and fail the whole day on any mid-stream failure** — loses the other providers' data for
   the day because one connection dropped.
3. **Buffer the raw bytes (e.g. to a temp file) and parse after a complete download** — atomic too, and smaller in
   memory, but adds disk I/O and a second pass, and a truncated body is only detected by parsing anyway.
4. **Deduplicate retried offers by record id** — the record id is a provider detail outside the domain; it would
   need a set of every id seen, which is the memory we are trying to avoid.

## Consequences

- **Positive:** partitions are all-or-nothing; retries can't duplicate offers; the aggregator stays append-only; the
  report's counts reconcile exactly.
- **Negative / trade-offs:** up to one full partition per in-flight request is held as objects before it is fed;
  a retry re-downloads and re-parses the whole partition.
- **Follow-ups:** if partitions grow much larger, buffer to disk (alternative 3) or ask providers for smaller pages.
