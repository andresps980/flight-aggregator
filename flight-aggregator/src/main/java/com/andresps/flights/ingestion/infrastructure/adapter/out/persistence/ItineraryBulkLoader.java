package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.ingestion.application.port.out.DaySnapshotWrite;
import java.sql.Connection;
import java.sql.SQLException;

/** Inserts a day's rows into {@code itinerary} on the given connection, inside the caller's transaction (ADR-0018). */
interface ItineraryBulkLoader {

    /** @return the number of rows inserted */
    long load(Connection connection, DaySnapshotWrite write) throws SQLException;
}
