package com.andresps.flights.ingestion.domain;

import java.util.Objects;

/**
 * The result of handing a day to the store (spec 004, AC-7, AC-9): its outcome, the rows stored and the itineraries
 * skipped because their first departure is on another UTC day ({@code stored + misfiled = itineraries produced}).
 */
public record DayPublication(DayOutcome outcome, int storedItineraries, int misfiledItineraries) {

    public DayPublication {
        Objects.requireNonNull(outcome, "outcome");
        if (storedItineraries < 0 || misfiledItineraries < 0) {
            throw new IllegalArgumentException(
                    "Counts must not be negative: stored " + storedItineraries + ", misfiled " + misfiledItineraries);
        }
        if (!outcome.stored() && (storedItineraries != 0 || misfiledItineraries != 0)) {
            throw new IllegalArgumentException(outcome + " stores nothing, got " + storedItineraries + " rows");
        }
    }

    public static DayPublication replaced(int stored, int misfiled) {
        return new DayPublication(DayOutcome.REPLACED, stored, misfiled);
    }

    public static DayPublication publishedIncomplete(int stored, int misfiled) {
        return new DayPublication(DayOutcome.PUBLISHED_INCOMPLETE, stored, misfiled);
    }

    public static DayPublication keptStale() {
        return new DayPublication(DayOutcome.KEPT_STALE, 0, 0);
    }

    public static DayPublication noData() {
        return new DayPublication(DayOutcome.NO_DATA, 0, 0);
    }

    public static DayPublication writeFailed() {
        return new DayPublication(DayOutcome.WRITE_FAILED, 0, 0);
    }
}
