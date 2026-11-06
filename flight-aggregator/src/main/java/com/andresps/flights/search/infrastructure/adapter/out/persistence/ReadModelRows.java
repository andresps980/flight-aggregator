package com.andresps.flights.search.infrastructure.adapter.out.persistence;

import com.andresps.flights.search.domain.Freshness;
import com.andresps.flights.search.domain.ItineraryView;
import com.andresps.flights.search.domain.SegmentView;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Maps read-model rows ({@code itinerary} joined with {@code day_snapshot}) to search values. */
final class ReadModelRows {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ReadModelRows() {}

    static ItineraryView itinerary(ResultSet rs) throws SQLException {
        return new ItineraryView(
                rs.getString("itinerary_key"),
                rs.getString("origin"),
                rs.getString("destination"),
                rs.getObject("departure_date", LocalDate.class),
                instant(rs, "departure_at"),
                instant(rs, "arrival_at"),
                rs.getString("airline"),
                rs.getInt("stops"),
                segments(rs.getString("segments")),
                rs.getBigDecimal("base_price"),
                rs.getBigDecimal("final_price"),
                rs.getString("selected_provider"),
                strings(rs.getArray("providers")),
                freshness(rs));
    }

    static Freshness freshness(ResultSet rs) throws SQLException {
        return Freshness.of(
                rs.getBoolean("complete"),
                instant(rs, "published_at"),
                strings(rs.getArray("missing_providers")),
                instant(rs, "stale_since"));
    }

    static List<String> strings(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** The {@code segments} JSONB written by the ingestion store (spec 004): returned as stored, in order. */
    private static List<SegmentView> segments(String json) {
        JsonNode array = JSON.readTree(json);
        List<SegmentView> segments = new ArrayList<>(array.size());
        for (JsonNode segment : array) {
            segments.add(new SegmentView(
                    segment.get("flightNumber").asString(),
                    segment.get("origin").asString(),
                    segment.get("destination").asString(),
                    Instant.parse(segment.get("departureAt").asString()),
                    Instant.parse(segment.get("arrivalAt").asString())));
        }
        return segments;
    }
}
