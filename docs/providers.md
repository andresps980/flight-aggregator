# Provider formats (gds-mock)

The three mock GDS providers served by `gds-mock` ([spec 002](../specs/002-gds-mock/spec.md)). Each has its own
legacy format **and its own way of writing times**, on purpose: the aggregator's adapters must normalise everything
to absolute instants and to one flight-number format before deduplicating.

| Provider | Endpoint | Content type | Times | Flight number | Record id |
|---|---|---|---|---|---|
| Mock-Alpha | `GET /mock-alpha/flights` | `application/json` | epoch milliseconds, UTC | carrier + number: `VY1` | `ALP-…` |
| Mock-Beta | `GET /mock-beta/flights` | `application/xml` | ISO-8601 with the airport's offset: `2026-11-06T03:05:00+00:00` | `<carrier>VY</carrier><flightNumber>1</flightNumber>` | `BET-…` |
| Mock-Gamma | `GET /mock-gamma/flights` | `text/csv;charset=UTF-8` | local date-time + IANA zone: `2026-11-06T03:05` + `Europe/Dublin` | zero-padded: `VY0001` | `GAM-…` |

All prices are EUR with two decimals. Record ids are `<prefix>-<partition day yyyyMMdd>-<index>-<copy>`: the part
in the middle identifies the world itinerary (the same in every provider) and `copy` is `1` for a same-provider
duplicate.

## The same itinerary from the three providers

Real output (`deterministic` profile, seed 42, reference date 2026-11-05): itinerary `20261106-000001`, VY1
Dublin → Buenos Aires + VY8227 Buenos Aires → Frankfurt, offered by all three providers at different prices.

### Mock-Alpha — raw JSON array

Flat-ish objects with legacy field names; one `legs` array per offer.

```json
[
  {"offerRef":"ALP-20261106-000001-0","cxr":"VY","totFare":433.51,"cur":"EUR",
   "legs":[{"fltNum":"VY1","org":"DUB","dst":"EZE","depTs":1793934300000,"arrTs":1793972400000},
           {"fltNum":"VY8227","org":"EZE","dst":"FRA","depTs":1793989500000,"arrTs":1794009300000}]}
]
```

| Field | Meaning |
|---|---|
| `offerRef` | record id |
| `cxr` | carrier of the first leg |
| `totFare`, `cur` | price (JSON number, 2 decimals) and currency |
| `legs[].fltNum` | carrier + flight number, no padding |
| `legs[].org`, `legs[].dst` | IATA codes |
| `legs[].depTs`, `legs[].arrTs` | epoch milliseconds (UTC) |

### Mock-Beta — deeply nested XML

`response > itineraries > itinerary > pricing` and `originDestinationOptions > option > segments > segment`.
(Indented here for reading; the feed is written without whitespace.)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<response provider="mock-beta">
  <itineraries>
    <itinerary id="BET-20261106-000001-0">
      <pricing><totalAmount currency="EUR">428.41</totalAmount></pricing>
      <originDestinationOptions>
        <option>
          <segments>
            <segment>
              <carrier>VY</carrier>
              <flightNumber>1</flightNumber>
              <departure airport="DUB" dateTime="2026-11-06T03:05:00+00:00"/>
              <arrival airport="EZE" dateTime="2026-11-06T10:40:00-03:00"/>
            </segment>
            <segment>
              <carrier>VY</carrier>
              <flightNumber>8227</flightNumber>
              <departure airport="EZE" dateTime="2026-11-06T15:25:00-03:00"/>
              <arrival airport="FRA" dateTime="2026-11-07T00:55:00+01:00"/>
            </segment>
          </segments>
        </option>
      </originDestinationOptions>
    </itinerary>
  </itineraries>
</response>
```

A zero offset is written `+00:00` (never `Z`).

### Mock-Gamma — comma-separated stream

A header line, then one itinerary per line: `REF,PRICE_EUR,SEGMENTS`. Lines end with `\n` (not RFC 4180's CRLF),
as many legacy feeds do, and no field is quoted (no value contains a comma). Segments are separated by `|`; each segment
has 7 `~`-separated sub-fields: `flight~origin~localDeparture~originZone~destination~localArrival~destinationZone`.

```text
REF,PRICE_EUR,SEGMENTS
GAM-20261106-000001-0,459.15,VY0001~DUB~2026-11-06T03:05~Europe/Dublin~EZE~2026-11-06T10:40~America/Argentina/Buenos_Aires|VY8227~EZE~2026-11-06T15:25~America/Argentina/Buenos_Aires~FRA~2026-11-07T00:55~Europe/Berlin
```

Local times only make sense with their zone: in the same feed, `OPO 2026-11-06T12:55 Europe/Lisbon` →
`BOS 2026-11-06T12:15 America/New_York` "lands before it takes off" in local time but is a 6 h 20 min flight.

## Normalisation the adapters must do

| | Alpha | Beta | Gamma |
|---|---|---|---|
| Instant | `Instant.ofEpochMilli(depTs)` | `OffsetDateTime.parse(dateTime).toInstant()` | `LocalDateTime.parse(t).atZone(ZoneId.of(zone)).toInstant()` |
| Flight number | `VY1` | `carrier + flightNumber` | strip leading zeros: `VY0001` → `VY1` |

Without this, the same flight from two providers produces two different itinerary keys (spec 001).

## Malformed records

About 1 % of records (default) are malformed in exactly one field. The rest of the record and the document stay
well-formed, so one bad record never breaks the feed.

| Kind | Alpha | Beta | Gamma |
|---|---|---|---|
| `BAD_AIRPORT_CODE` | `"org":"M4D"` | `airport="M4D"` | `~M4D~` |
| `NON_POSITIVE_PRICE` | `"totFare":0.00` or negative | `<totalAmount …>-12.50</totalAmount>` | `,-12.50,` |
| `UNPARSEABLE_TIME` (a departure) | `"depTs":"TBD"` (string in a numeric field) | `dateTime="2026-11-10T25:61:00+01:00"` | `~10/11/2026 9h~` |

A digit replaces the middle letter of the origin code of one segment. The other providers' records of the same
itinerary are not affected (malformations are drawn per record).

## Business-invalid itineraries

About 5 % of itineraries (default) break one spec 001 rule, and that rule is always the **first** failing one in
spec 001's evaluation order, so discard counts can be reconciled:

| Kind | How it is generated |
|---|---|
| `MIXED_AIRLINES` | two chained segments, the second one by another airline |
| `ARRIVAL_NOT_AFTER_DEPARTURE` | a direct flight arriving 0–120 min before (or at) its departure |
| `SEGMENTS_NOT_CHAINED` | second segment departs from a different airport than the first one's destination |
| `REVISITED_AIRPORT` | `A → B → A → C` |
| `CONNECTION_TOO_SHORT` | connection between −30 and 40 min |
| `LAYOVER_TOO_LONG` | connection between 24 h 05 min and 48 h |
| `ALREADY_DEPARTED` | a valid schedule departing the day **before** the reference date, served under its partition day |

They are offered (and duplicated) by providers like valid itineraries. `GET /admin/world/stats` reports the exact
counts and the figures spec 001 should produce (see [usage](usage.md#expected-figures)).
