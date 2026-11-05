package com.andresps.flights.ingestion.domain;

import java.util.EnumMap;
import java.util.Map;

/** Helpers for "malformed records per reason" maps: immutable, without zero entries. */
final class MalformedCounts {

    private MalformedCounts() {}

    static Map<MalformedReason, Long> nonZero(Map<MalformedReason, Long> counts) {
        Map<MalformedReason, Long> copy = new EnumMap<>(MalformedReason.class);
        counts.forEach((reason, count) -> {
            if (count < 0) {
                throw new IllegalArgumentException("Negative malformed count for " + reason + ": " + count);
            }
            if (count > 0) {
                copy.put(reason, count);
            }
        });
        return Map.copyOf(copy);
    }

    static long total(Map<MalformedReason, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }
}
