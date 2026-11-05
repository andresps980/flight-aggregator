package com.andresps.flights.ingestion.domain;

import java.util.Collection;

/** Outcome of a provider or of a whole ingestion run (spec 003, AC-19). */
public enum IngestionStatus {
    COMPLETED,
    PARTIAL,
    FAILED;

    /** A provider: COMPLETED if every partition succeeded, FAILED if none did, PARTIAL otherwise. */
    public static IngestionStatus ofPartitions(int succeeded, int failed) {
        if (succeeded < 0 || failed < 0 || succeeded + failed == 0) {
            throw new IllegalArgumentException(
                    "A provider needs at least one partition, got " + succeeded + " succeeded / " + failed + " failed");
        }
        if (failed == 0) {
            return COMPLETED;
        }
        return succeeded == 0 ? FAILED : PARTIAL;
    }

    /** A run: COMPLETED if every provider completed, FAILED only if every provider failed, PARTIAL otherwise. */
    public static IngestionStatus overall(Collection<IngestionStatus> providers) {
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("A run needs at least one provider");
        }
        if (providers.stream().allMatch(COMPLETED::equals)) {
            return COMPLETED;
        }
        return providers.stream().allMatch(FAILED::equals) ? FAILED : PARTIAL;
    }
}
