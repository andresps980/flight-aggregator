package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.aggregation.domain.model.Segment;
import java.util.List;

/**
 * The {@code segments} JSONB value: {@code [{"flightNumber","origin","destination","departureAt","arrivalAt"}]} in
 * travel order, written by hand because it runs once per row on the bulk-load path (ADR-0018).
 */
final class SegmentsJson {

    private SegmentsJson() {}

    static String of(List<Segment> segments) {
        StringBuilder json = new StringBuilder(160 * segments.size());
        json.append('[');
        for (Segment segment : segments) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"flightNumber\":");
            string(json, segment.flightNumber().toString());
            json.append(",\"origin\":");
            string(json, segment.origin().value());
            json.append(",\"destination\":");
            string(json, segment.destination().value());
            json.append(",\"departureAt\":");
            string(json, segment.departure().toString());
            json.append(",\"arrivalAt\":");
            string(json, segment.arrival().toString());
            json.append('}');
        }
        return json.append(']').toString();
    }

    private static void string(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
