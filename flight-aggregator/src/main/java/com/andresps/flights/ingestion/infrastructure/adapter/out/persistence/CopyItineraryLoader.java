package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.ingestion.application.port.out.DaySnapshotWrite;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;

/** Loads rows with {@code COPY itinerary (...) FROM STDIN}, streamed through PgJDBC's copy API (ADR-0018). */
final class CopyItineraryLoader implements ItineraryBulkLoader {

    static final String COPY_SQL =
            "COPY itinerary (" + String.join(", ", ItineraryCopyEncoder.COLUMNS) + ") FROM STDIN";

    private final ItineraryCopyEncoder encoder;

    CopyItineraryLoader(ItineraryCopyEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public long load(Connection connection, DaySnapshotWrite write) throws SQLException {
        PGCopyOutputStream copy = new PGCopyOutputStream(connection.unwrap(PGConnection.class), COPY_SQL);
        try {
            encoder.write(write, copy);
            return copy.endCopy();
        } catch (IOException e) {
            cancel(copy);
            throw new SQLException("COPY of " + write.day() + " failed", e);
        } catch (SQLException | RuntimeException e) {
            cancel(copy);
            throw e;
        }
    }

    private static void cancel(PGCopyOutputStream copy) {
        if (copy.isActive()) {
            try {
                copy.cancelCopy();
            } catch (SQLException ignored) {
                // the transaction is rolled back anyway
            }
        }
    }

    @Override
    public String toString() {
        return "COPY";
    }
}
