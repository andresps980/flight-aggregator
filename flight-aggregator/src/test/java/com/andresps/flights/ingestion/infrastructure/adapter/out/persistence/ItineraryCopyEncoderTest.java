package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.complete;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itineraries;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itinerary;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0018: rows encoded in PostgreSQL's COPY text format, escaped, and streamed in bounded chunks. */
class ItineraryCopyEncoderTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-06");
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final int BUFFER = 8 * 1024;

    private final ItineraryCopyEncoder encoder = new ItineraryCopyEncoder(BUFFER);

    private String encode(List<AggregatedItinerary> rows) throws IOException {
        var out = new ByteArrayOutputStream();
        encoder.write(complete(DAY, RUN, rows), out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-10/AC-11: one line per itinerary, one tab-separated field per column, in column order")
    void lineLayout() throws IOException {
        AggregatedItinerary connecting = itinerary(DAY, 0); // two segments, offered by alpha and beta

        String[] fields = encode(List.of(connecting)).split("\n", -1)[0].split("\t", -1);

        assertThat(ItineraryCopyEncoder.COLUMNS)
                .containsExactly(
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
        assertThat(fields).hasSize(ItineraryCopyEncoder.COLUMNS.size());
        assertThat(fields[0]).isEqualTo(connecting.key().value());
        assertThat(fields[1]).isEqualTo("2026-11-06");
        assertThat(fields[2]).isEqualTo("MAD");
        assertThat(fields[3]).isEqualTo("LHR");
        assertThat(fields[4]).isEqualTo("2026-11-06T00:00:00Z");
        assertThat(fields[5]).isEqualTo("2026-11-06T04:30:00Z");
        assertThat(fields[6]).isEqualTo("IB");
        assertThat(fields[7]).isEqualTo("1");
        assertThat(fields[8]).isEqualTo("50.00");
        assertThat(fields[9]).isEqualTo("52.50");
        assertThat(fields[10]).isEqualTo("mock-alpha");
        assertThat(fields[11]).isEqualTo("{\"mock-alpha\",\"mock-beta\"}");
        assertThat(fields[12])
                .startsWith("[{\"flightNumber\":\"IB1\",\"origin\":\"MAD\",\"destination\":\"BCN\"")
                .contains("\"departureAt\":\"2026-11-06T00:00:00Z\"")
                .contains("{\"flightNumber\":\"IB5000\"");
        assertThat(fields[13]).isEqualTo(RUN.toString());
        assertThat(fields[14]).isEqualTo("2026-11-05T12:00:00Z");
    }

    @Test
    @DisplayName("AC-11: tabs, newlines, carriage returns, backslashes and quotes cannot break a row or an array")
    void escaping() throws IOException {
        ProviderId hostile = new ProviderId("evil\t\"x\",{y}\\z\nnext\rline");
        AggregatedItinerary base = itinerary(DAY, 1);
        AggregatedItinerary row = new AggregatedItinerary(
                base.key(),
                new com.andresps.flights.aggregation.domain.model.FlightOffer(
                        base.itinerary(), hostile, base.basePrice()),
                base.finalPrice(),
                new TreeSet<>(List.of(hostile, SnapshotFixtures.ALPHA)));

        String encoded = encode(List.of(row, itinerary(DAY, 2)));

        String[] lines = encoded.split("\n", -1);
        assertThat(lines).hasSize(3);
        assertThat(lines[2]).isEmpty();
        assertThat(lines[0].split("\t", -1)).hasSize(ItineraryCopyEncoder.COLUMNS.size());
        assertThat(lines[0]).doesNotContain("\r");
        assertThat(ItineraryCopyEncoder.text("a\tb\nc\rd\\e")).isEqualTo("a\\tb\\nc\\rd\\\\e");
        assertThat(ItineraryCopyEncoder.arrayLiteral(List.of("p\"q", "r\\s")))
                .as("array quoting first, then COPY escaping doubles the backslashes")
                .isEqualTo("{\"p\\\\\"q\",\"r\\\\\\\\s\"}");
    }

    @Test
    @DisplayName("AC-11: rows are streamed in chunks no larger than the buffer, never as one large buffer")
    void streamsInBoundedChunks() throws IOException {
        var out = new ChunkRecorder();

        encoder.write(complete(DAY, RUN, itineraries(DAY, 20_000)), out);

        assertThat(out.total).isGreaterThan(20L * BUFFER);
        assertThat(out.largestChunk).isLessThanOrEqualTo(BUFFER);
        assertThat(out.chunks).isGreaterThan(20);
    }

    /** Records the size of every write it receives. */
    private static final class ChunkRecorder extends OutputStream {

        private long total;
        private int largestChunk;
        private int chunks;

        @Override
        public void write(int b) {
            record(1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            record(length);
        }

        private void record(int length) {
            total += length;
            chunks++;
            largestChunk = Math.max(largestChunk, length);
        }
    }
}
