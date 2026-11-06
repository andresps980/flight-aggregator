package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotWrite;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * Encodes a day's rows in PostgreSQL's {@code COPY ... FROM STDIN} text format (ADR-0018) and writes them to a stream
 * in chunks of at most {@code bufferSize} bytes (a row longer than the buffer is written on its own), so a day is
 * never materialised as one large buffer.
 *
 * <p>Text format: one line per row, fields separated by tabs; backslash, tab, newline and carriage return are escaped
 * with a backslash, so no value can break a row. Arrays are written as quoted array literals and segments as JSON,
 * both then escaped like any other text. No column is ever null.
 */
final class ItineraryCopyEncoder {

    static final List<String> COLUMNS = List.of(
            "itinerary_key",
            "departure_date",
            "origin",
            "destination",
            "departure_at",
            "arrival_at",
            "airline",
            "stops",
            "base_price",
            "final_price",
            "selected_provider",
            "providers",
            "segments",
            "run_id",
            "created_at");

    private final int bufferSize;

    ItineraryCopyEncoder(int bufferSize) {
        if (bufferSize < 1024) {
            throw new IllegalArgumentException("bufferSize must be at least 1 KiB, got " + bufferSize);
        }
        this.bufferSize = bufferSize;
    }

    void write(DaySnapshotWrite write, OutputStream out) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int used = 0;
        StringBuilder line = new StringBuilder(512);
        String suffix = "\t" + write.runId() + "\t" + write.publishedAt() + "\n";
        for (AggregatedItinerary itinerary : write.itineraries()) {
            line.setLength(0);
            appendRow(line, itinerary, write);
            line.append(suffix);
            byte[] row = line.toString().getBytes(StandardCharsets.UTF_8);
            if (used + row.length > buffer.length) {
                out.write(buffer, 0, used);
                used = 0;
            }
            if (row.length > buffer.length) {
                out.write(row);
            } else {
                System.arraycopy(row, 0, buffer, used, row.length);
                used += row.length;
            }
        }
        if (used > 0) {
            out.write(buffer, 0, used);
        }
    }

    private static void appendRow(StringBuilder line, AggregatedItinerary itinerary, DaySnapshotWrite write) {
        List<Segment> segments = itinerary.itinerary().segments();
        Segment first = segments.getFirst();
        Segment last = segments.getLast();
        line.append(text(itinerary.key().value()))
                .append('\t')
                .append(write.day())
                .append('\t')
                .append(first.origin().value())
                .append('\t')
                .append(last.destination().value())
                .append('\t')
                .append(first.departure())
                .append('\t')
                .append(last.arrival())
                .append('\t')
                .append(first.airline().value())
                .append('\t')
                .append(segments.size() - 1)
                .append('\t')
                .append(itinerary.basePrice().amount().toPlainString())
                .append('\t')
                .append(itinerary.finalPrice().amount().toPlainString())
                .append('\t')
                .append(text(itinerary.selectedOffer().provider().value()))
                .append('\t')
                .append(arrayLiteral(
                        itinerary.providers().stream().map(ProviderId::value).toList()))
                .append('\t')
                .append(text(SegmentsJson.of(segments)));
    }

    /** Escapes a value for the COPY text format. */
    static String text(String value) {
        StringBuilder escaped = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String replacement = switch (c) {
                case '\\' -> "\\\\";
                case '\t' -> "\\t";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                default -> null;
            };
            if (replacement != null && escaped == null) {
                escaped = new StringBuilder(value.length() + 8).append(value, 0, i);
            }
            if (escaped != null) {
                if (replacement != null) {
                    escaped.append(replacement);
                } else {
                    escaped.append(c);
                }
            }
        }
        return escaped == null ? value : escaped.toString();
    }

    /** A text[] literal with every element quoted ({@code "} and {@code \} escaped), then COPY-escaped. */
    static String arrayLiteral(Collection<String> elements) {
        StringBuilder literal = new StringBuilder("{");
        for (String element : elements) {
            if (literal.length() > 1) {
                literal.append(',');
            }
            literal.append('"')
                    .append(element.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return text(literal.append('}').toString());
    }
}
