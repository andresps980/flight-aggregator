package com.andresps.gdsmock.support;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A provider record as a client would see it after parsing any of the three formats, with flight numbers normalised
 * (carrier + number without leading zeros) and times converted to instants.
 *
 * @param recordId the record id as sent, including the provider prefix (e.g. {@code ALP-20261110-000017-0})
 * @param price the price as sent
 */
public record ParsedRecord(String recordId, String price, List<TestSegment> segments) {

    private static final String IATA = "[A-Z]{3}";

    public BigDecimal priceValue() {
        return new BigDecimal(price);
    }

    /** Partition day and index shared by every record of the same world itinerary, e.g. {@code 20261110-000017}. */
    public String itineraryRef() {
        String withoutPrefix = recordId.substring(recordId.indexOf('-') + 1);
        return withoutPrefix.substring(0, withoutPrefix.lastIndexOf('-'));
    }

    public boolean sameProviderDuplicate() {
        return recordId.endsWith("-1");
    }

    public boolean hasBadAirportCode() {
        return segments.stream()
                .anyMatch(s -> !s.origin().matches(IATA) || !s.destination().matches(IATA));
    }

    public boolean hasNonPositivePrice() {
        return priceValue().signum() <= 0;
    }

    public boolean hasUnparseableTime() {
        return segments.stream().anyMatch(s -> s.departure() == null || s.arrival() == null);
    }

    public boolean malformed() {
        return hasBadAirportCode() || hasNonPositivePrice() || hasUnparseableTime();
    }

    /** Spec 001 identity: flight numbers, airports and departure instants. */
    public String identity() {
        return segments.stream()
                .map(s -> s.flight() + ":" + s.origin() + "-" + s.destination() + "@" + s.departure())
                .collect(Collectors.joining("|"));
    }
}
