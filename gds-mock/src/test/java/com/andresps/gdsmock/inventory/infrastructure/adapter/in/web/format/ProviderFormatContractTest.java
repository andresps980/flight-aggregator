package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format;

import static com.andresps.gdsmock.support.WorldFixtures.generator;
import static com.andresps.gdsmock.support.WorldFixtures.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.gdsmock.inventory.domain.model.Airport;
import com.andresps.gdsmock.inventory.domain.model.Malformation;
import com.andresps.gdsmock.inventory.domain.model.MalformationKind;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormat;
import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.support.ParsedRecord;
import com.andresps.gdsmock.support.ProviderPayloads;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link ProviderFormat} honours (LSP): its output parses back to exactly the records it was given,
 * an empty feed is a valid document, and output is written while records are still being produced.
 */
abstract class ProviderFormatContractTest {

    static final Airport MAD = new Airport("MAD", ZoneId.of("Europe/Madrid"));
    static final Airport BCN = new Airport("BCN", ZoneId.of("Europe/Madrid"));
    static final Airport LHR = new Airport("LHR", ZoneId.of("Europe/London"));
    static final Airport JFK = new Airport("JFK", ZoneId.of("America/New_York"));
    static final Airport MIA = new Airport("MIA", ZoneId.of("America/New_York"));

    /** The format under test. */
    abstract ProviderFormat format();

    /** The independent client-side parser for this format. */
    abstract List<ParsedRecord> parse(byte[] body);

    /** The record-id prefix this provider uses. */
    abstract String prefix();

    ProviderId provider() {
        return format().providerId();
    }

    /** IB345 MAD→BCN, 2026-11-10 08:00Z – 09:25Z (09:00 – 10:25 in Madrid), 129.90 EUR. */
    ProviderRecord direct() {
        return new ProviderRecord(
                provider(),
                "20261110-000017-0",
                0,
                List.of(segment("IB", 345, MAD, BCN, "2026-11-10T08:00:00Z", "2026-11-10T09:25:00Z")),
                new BigDecimal("129.90"),
                Optional.empty());
    }

    /** BA7 LHR→JFK + BA1203 JFK→MIA: London at +00:00, New York at -05:00 in November. */
    ProviderRecord connection() {
        return new ProviderRecord(
                provider(),
                "20261110-000018-0",
                0,
                List.of(
                        segment("BA", 7, LHR, JFK, "2026-11-10T10:00:00Z", "2026-11-10T18:00:00Z"),
                        segment("BA", 1203, JFK, MIA, "2026-11-10T20:00:00Z", "2026-11-10T23:10:00Z")),
                new BigDecimal("450.00"),
                Optional.empty());
    }

    ProviderRecord malformed(MalformationKind kind) {
        ProviderRecord base = connection();
        List<ScheduledSegment> segments = base.segments();
        BigDecimal price = base.price();
        if (kind == MalformationKind.BAD_AIRPORT_CODE) {
            ScheduledSegment first = segments.getFirst();
            segments = List.of(
                    new ScheduledSegment(
                            first.airline(),
                            first.flightNumber(),
                            new Airport("L4R", first.origin().zone()),
                            first.destination(),
                            first.departure(),
                            first.arrival()),
                    segments.get(1));
        }
        if (kind == MalformationKind.NON_POSITIVE_PRICE) {
            price = new BigDecimal("-12.50");
        }
        return new ProviderRecord(
                provider(), "20261110-000019-1", 1, segments, price, Optional.of(new Malformation(kind, 1)));
    }

    byte[] write(List<ProviderRecord> records) {
        var out = new ByteArrayOutputStream();
        try {
            format().write(records.iterator(), out);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    List<ParsedRecord> expected(List<ProviderRecord> records) {
        return records.stream().map(r -> ProviderPayloads.expected(r, prefix())).toList();
    }

    @Test
    @DisplayName("AC-11: hand-built records (direct, connection across time zones) parse back exactly")
    void handBuiltRoundTrip() {
        var records = List.of(direct(), connection());

        assertThat(parse(write(records))).isEqualTo(expected(records));
    }

    @Test
    @DisplayName("AC-7, AC-11: every malformation breaks only its field and the document still parses")
    void malformedRecordsKeepTheDocumentParseable() {
        var records = List.of(
                direct(),
                malformed(MalformationKind.BAD_AIRPORT_CODE),
                malformed(MalformationKind.NON_POSITIVE_PRICE),
                malformed(MalformationKind.UNPARSEABLE_TIME),
                connection());

        List<ParsedRecord> parsed = parse(write(records));

        assertThat(parsed).isEqualTo(expected(records));
        assertThat(parsed.get(1).hasBadAirportCode()).isTrue();
        assertThat(parsed.get(2).hasNonPositivePrice()).isTrue();
        assertThat(parsed.get(3).hasUnparseableTime()).isTrue();
        assertThat(parsed.get(3).segments().get(1).departure()).isNull();
        assertThat(List.of(parsed.get(0), parsed.get(4))).noneMatch(ParsedRecord::malformed);
    }

    @Test
    @DisplayName("AC-11: this provider's records of a generated world parse back exactly, in order")
    void generatedWorldRoundTrip() {
        var records = generator(settings(7L, 2_000, 0.1, 0.05, 0.05))
                .records(provider())
                .toList();

        assertThat(parse(write(records))).isEqualTo(expected(records));
    }

    @Test
    @DisplayName("AC-11: an empty feed is a valid, empty document")
    void emptyFeed() {
        assertThat(parse(write(List.of()))).isEmpty();
    }

    @Test
    @DisplayName("AC-12: output is written while records are still being produced (probe at 25,000 of 50,000)")
    void streamsWhileProducing() throws IOException {
        assertStreams(50_000);
    }

    @Test
    @Tag("slow")
    @DisplayName("AC-12: 1,000,000 records are written while being produced (slow)")
    void streamsAMillionRecords() throws IOException {
        assertStreams(1_000_000);
    }

    @Test
    @DisplayName("AC-12: when the client disconnects, writing fails fast and records stop being consumed")
    void stopsConsumingWhenTheClientDisconnects() {
        var out = new DisconnectingOutputStream(64 * 1024);
        var records = new ProbingIterator(direct(), 1_000_000, 0, new CountingOutputStream());

        assertThatThrownBy(() -> format().write(records, out)).isInstanceOf(IOException.class);
        assertThat(records.produced).isLessThan(50_000);
    }

    private void assertStreams(int total) throws IOException {
        var out = new CountingOutputStream();
        var records = new ProbingIterator(direct(), total, total / 2, out);

        format().write(records, out);

        assertThat(records.bytesWrittenAtProbe).isPositive();
        assertThat(out.count).isGreaterThan(records.bytesWrittenAtProbe * 3 / 2);
    }

    static ScheduledSegment segment(
            String airline, int number, Airport origin, Airport destination, String departure, String arrival) {
        return new ScheduledSegment(
                airline, number, origin, destination, Instant.parse(departure), Instant.parse(arrival));
    }

    /** Discards bytes, only counting them. */
    static final class CountingOutputStream extends OutputStream {
        long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }

    /** Accepts {@code limit} bytes, then fails like a socket whose client went away. */
    static final class DisconnectingOutputStream extends OutputStream {
        private final long limit;
        private long count;

        DisconnectingOutputStream(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count += len;
            if (count > limit) {
                throw new IOException("Broken pipe");
            }
        }
    }

    /** Yields the same record {@code total} times and records how many bytes were written at {@code probeAt}. */
    static final class ProbingIterator implements Iterator<ProviderRecord> {
        private final ProviderRecord record;
        private final int total;
        private final int probeAt;
        private final CountingOutputStream out;
        int produced;
        long bytesWrittenAtProbe = -1;

        ProbingIterator(ProviderRecord record, int total, int probeAt, CountingOutputStream out) {
            this.record = record;
            this.total = total;
            this.probeAt = probeAt;
            this.out = out;
        }

        @Override
        public boolean hasNext() {
            return produced < total;
        }

        @Override
        public ProviderRecord next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (produced == probeAt) {
                bytesWrittenAtProbe = out.count;
            }
            produced++;
            return record;
        }
    }
}
