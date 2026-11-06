package com.andresps.flights.ingestion.application.port.out;

import java.time.LocalDate;

/** Housekeeping of the itinerary read model around each run (spec 004, AC-12, AC-13). */
public interface SnapshotMaintenance {

    /** Deletes the rows and snapshot status of every day before {@code day}. */
    void deleteDaysBefore(LocalDate day);

    /** Refreshes the query planner's statistics after a run's writes. */
    void refreshStatistics();
}
