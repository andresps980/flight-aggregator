package com.andresps.flights.ingestion.infrastructure.adapter.out.provider.gamma;

import com.andresps.flights.aggregation.domain.model.AirportCode;
import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.FlightNumber;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.FeedParser;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.MalformedDocumentException;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.MalformedRecordException;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.PartitionBuffer;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.RecordValues;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Mock-Gamma: a header line, then one itinerary per line (docs/providers.md), read line by line. Every line must end
 * with a line feed, the last one included, so a body cut off mid-line is detected as truncated. CRLF endings and blank
 * lines are tolerated. A line longer than {@link #MAX_LINE_LENGTH} fails the document, bounding memory on a broken feed.
 */
public final class GammaCsvParser implements FeedParser {

    public static final ProviderId PROVIDER = new ProviderId("mock-gamma");
    /** Spec 008: each line declares its own currency. */
    static final String HEADER = "REF,PRICE,CURRENCY,SEGMENTS";

    static final int MAX_LINE_LENGTH = 64 * 1024;

    private static final Pattern FIELDS = Pattern.compile(",");
    private static final Pattern SEGMENTS = Pattern.compile("\\|");
    private static final Pattern SUB_FIELDS = Pattern.compile("~");
    private static final int FIELD_COUNT = 4;
    private static final int SUB_FIELD_COUNT = 7;

    @Override
    public ProviderId provider() {
        return PROVIDER;
    }

    @Override
    public void parse(InputStream body, ExchangeRates rates, PartitionBuffer buffer) throws IOException {
        // not closed: closing the reader would close the caller's body
        LineReader lines = new LineReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String header = lines.next();
        if (header == null) {
            throw new MalformedDocumentException("Mock-Gamma feed is empty (no header line)");
        }
        if (!HEADER.equals(header)) {
            throw new MalformedDocumentException("Mock-Gamma feed has an unexpected header: " + abbreviate(header));
        }
        for (String line = lines.next(); line != null; line = lines.next()) {
            if (line.isBlank()) {
                continue;
            }
            try {
                buffer.addOffer(toOffer(line, rates));
            } catch (MalformedRecordException e) {
                buffer.addMalformed(e.reason());
            }
        }
    }

    /** {@code REF,PRICE,CURRENCY,SEGMENTS}, checked in that order; the price is converted to EUR (spec 008). */
    private static FlightOffer toOffer(String line, ExchangeRates rates) {
        String[] fields = FIELDS.split(line, -1);
        if (fields.length != FIELD_COUNT) {
            throw RecordValues.invalid("expected " + FIELD_COUNT + " fields, got " + fields.length);
        }
        BigDecimal amount = RecordValues.amount(fields[1]);
        Money price = RecordValues.toEur(amount, RecordValues.currency(fields[2]), rates);
        String[] rawSegments = SEGMENTS.split(fields[3], -1);
        List<Segment> segments = new ArrayList<>(rawSegments.length);
        for (String rawSegment : rawSegments) {
            segments.add(toSegment(rawSegment));
        }
        return RecordValues.offer(segments, PROVIDER, price);
    }

    /** {@code flight~origin~localDeparture~originZone~destination~localArrival~destinationZone}, in that order. */
    private static Segment toSegment(String raw) {
        String[] parts = SUB_FIELDS.split(raw, -1);
        if (parts.length != SUB_FIELD_COUNT) {
            throw RecordValues.invalid("expected " + SUB_FIELD_COUNT + " segment sub-fields, got " + parts.length);
        }
        FlightNumber flight = RecordValues.flightNumber(parts[0]);
        AirportCode origin = RecordValues.airport(parts[1]);
        LocalDateTime localDeparture = RecordValues.localDateTime(parts[2]);
        ZoneId originZone = RecordValues.zone(parts[3]);
        AirportCode destination = RecordValues.airport(parts[4]);
        LocalDateTime localArrival = RecordValues.localDateTime(parts[5]);
        ZoneId destinationZone = RecordValues.zone(parts[6]);
        Instant departure = localDeparture.atZone(originZone).toInstant();
        Instant arrival = localArrival.atZone(destinationZone).toInstant();
        return RecordValues.segment(flight, origin, destination, departure, arrival);
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    /** Reads {@code \n}-terminated lines (a trailing {@code \r} is dropped), refusing unterminated or huge lines. */
    private static final class LineReader {

        private final Reader reader;
        private final char[] chunk = new char[8192];
        private int position;
        private int limit;
        private final StringBuilder line = new StringBuilder(256);

        LineReader(Reader reader) {
            this.reader = reader;
        }

        /** The next line without its terminator, or {@code null} at the end of a complete feed. */
        String next() throws IOException {
            line.setLength(0);
            while (true) {
                if (position == limit) {
                    limit = reader.read(chunk);
                    position = 0;
                    if (limit < 0) {
                        limit = 0;
                        if (line.isEmpty()) {
                            return null;
                        }
                        throw new MalformedDocumentException(
                                "Mock-Gamma feed was truncated: the last line has no line ending");
                    }
                }
                while (position < limit) {
                    char c = chunk[position++];
                    if (c == '\n') {
                        int end = line.length();
                        if (end > 0 && line.charAt(end - 1) == '\r') {
                            line.setLength(end - 1);
                        }
                        return line.toString();
                    }
                    line.append(c);
                    if (line.length() > MAX_LINE_LENGTH) {
                        throw new MalformedDocumentException(
                                "Mock-Gamma feed has a line longer than " + MAX_LINE_LENGTH + " characters");
                    }
                }
            }
        }
    }
}
