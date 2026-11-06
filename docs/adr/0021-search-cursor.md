# ADR-0021: Opaque, unsigned keyset cursor with a filter fingerprint

- **Status:** Accepted
- **Date:** 2026-11-06
- **Related spec:** [specs/005-search-api](../../specs/005-search-api/spec.md) (AC-7, AC-8, AC-16)
- **Refines:** [ADR-0008](0008-keyset-pagination.md) (follow-up: cursor encoding and validation)

## Context

Keyset pagination (ADR-0008) needs the last `(final_price, id)` of a page to fetch the next one. The client must
send it back without understanding it, so the format can change. Two mistakes must be caught: a cursor sent with
different filters (it would seek a position that means nothing for the new filters, and silently return a wrong
"next page"), and a corrupted or invented cursor. The data is public — anyone can run any search — and row ids
change on every day replacement (spec 004).

## Decision

We will encode the cursor as **base64url without padding** of the text `v1|<finalPrice>|<id>|<fingerprint>`:

- `finalPrice` as a plain decimal with 2 decimals, `id` as a decimal `long`: the last row of the page.
- `fingerprint`: the first 64 bits (16 hex characters) of SHA-256 over the **normalized filters** — upper-cased
  codes, `date` as ISO, `maxPrice` without trailing zeros, absent filters as empty — so `maxPrice=100` and
  `100.00` share cursors. `limit` is excluded: a client may change the page size while paging.
- Decoding is strict: wrong base64, a version other than `v1`, a wrong number of fields, a non-numeric or negative
  price or id, or a fingerprint that is not 16 hex characters → `400`. A valid cursor whose fingerprint differs
  from the request's filters → `400`.
- The codec is plain Java in the `search` domain; the use case decodes, checks the fingerprint, and encodes the next
  cursor from the last row of the page (which the query port returns explicitly, so a cached page that dropped
  departed items still continues from the right place, ADR-0022).
- The cursor is **not signed**. An edited `finalPrice` or `id` only moves the starting point of a search the client
  could run anyway: all rows are public, the query is parameterised, and the filters are still the request's own.
  A fingerprint recomputed by hand gains nothing either. A signature would need a shared secret in every instance and
  in compose, and rotating it would break every open cursor, for no protection of anything.
- Cursors do **not** encode the data version: a cursor survives an ingestion run and continues by position, with the
  documented skip/repeat at the boundary (spec 005, *Pagination notes*).

## Alternatives considered

1. **HMAC-signed cursor** — rejects tampering, but there is nothing to protect (above), and it adds a secret to manage
   and rotate. Rejected.
2. **Plain query parameters (`afterPrice`, `afterId`)** — transparent, but clients start building positions
   themselves, the format cannot evolve, and nothing ties the position to the filters. Rejected.
3. **Encrypted cursor** — hides the price and id, which are already in the response. Rejected.
4. **JSON instead of a delimited string** — self-describing but longer in URLs, and the fields are fixed. Rejected;
   the `v1` prefix leaves room for a different layout later.
5. **Encoding the data version and rejecting stale cursors** — exact pages, but every 15-min run would send users
   back to page 1 mid-browse. Rejected (spec 005).

## Consequences

- **Positive:** short (≈ 40 characters), URL-safe, versioned; filter mix-ups fail loudly instead of returning
  plausible but wrong pages; cache keys can use the decoded position (ADR-0022).
- **Negative / trade-offs:** a client can craft a cursor to start anywhere (harmless); 64-bit fingerprints can collide
  in theory (≈ 2⁻⁶⁴ per pair of filter sets), which would merely accept a cursor for other filters.
- **Follow-ups:** another sort order needs a new cursor version (`v2`) carrying its own key.
