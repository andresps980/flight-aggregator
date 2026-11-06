package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotWrite;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The alternative measured in ADR-0018: batched {@code INSERT}s (with PgJDBC's {@code reWriteBatchedInserts=true} each
 * batch becomes multi-row inserts). COPY ({@link CopyItineraryLoader}) won, so this lives in test sources only, to keep
 * the comparison reproducible ({@code BulkLoadBenchmarkIT}) and to check it stores the same rows.
 */
final class BatchInsertItineraryLoader implements ItineraryBulkLoader {

    private static final String INSERT_SQL = "INSERT INTO itinerary ("
            + String.join(", ", ItineraryCopyEncoder.COLUMNS)
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)";

    private final int batchSize;

    BatchInsertItineraryLoader(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive, got " + batchSize);
        }
        this.batchSize = batchSize;
    }

    @Override
    public long load(Connection connection, DaySnapshotWrite write) throws SQLException {
        long rows = 0;
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(write.publishedAt(), ZoneOffset.UTC);
        try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
            int pending = 0;
            for (AggregatedItinerary itinerary : write.itineraries()) {
                List<Segment> segments = itinerary.itinerary().segments();
                Segment first = segments.getFirst();
                Segment last = segments.getLast();
                insert.setString(1, itinerary.key().value());
                insert.setObject(2, write.day());
                insert.setString(3, first.origin().value());
                insert.setString(4, last.destination().value());
                insert.setObject(5, OffsetDateTime.ofInstant(first.departure(), ZoneOffset.UTC));
                insert.setObject(6, OffsetDateTime.ofInstant(last.arrival(), ZoneOffset.UTC));
                insert.setString(7, first.airline().value());
                insert.setShort(8, (short) (segments.size() - 1));
                insert.setBigDecimal(9, itinerary.basePrice().amount());
                insert.setBigDecimal(10, itinerary.finalPrice().amount());
                insert.setString(11, itinerary.selectedOffer().provider().value());
                insert.setArray(
                        12,
                        connection.createArrayOf(
                                "text",
                                itinerary.providers().stream()
                                        .map(ProviderId::value)
                                        .toArray()));
                insert.setString(13, SegmentsJson.of(segments));
                insert.setObject(14, write.runId());
                insert.setObject(15, createdAt);
                insert.addBatch();
                if (++pending == batchSize) {
                    rows += executed(insert.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) {
                rows += executed(insert.executeBatch());
            }
        }
        return rows;
    }

    /** Rewritten batches report {@code SUCCESS_NO_INFO} (-2) per row: count those as one row each. */
    private static long executed(int[] counts) {
        long rows = 0;
        for (int count : counts) {
            rows += count == PreparedStatement.SUCCESS_NO_INFO ? 1 : count;
        }
        return rows;
    }

    @Override
    public String toString() {
        return "batched INSERT";
    }
}
