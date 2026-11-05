package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format;

import com.andresps.gdsmock.inventory.domain.model.Airport;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormat;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import org.springframework.http.MediaType;

/**
 * Mock-Gamma: a comma-separated stream, one itinerary per line. The last field holds the segments separated by
 * {@code |}, each with 7 {@code ~}-separated sub-fields: flight (zero-padded to 4 digits), origin, local departure,
 * origin zone, destination, local arrival, destination zone.
 *
 * <pre>{@code REF,PRICE_EUR,SEGMENTS
 * GAM-20261110-000017-0,127.15,IB0345~MAD~2026-11-10T09:00~Europe/Madrid~BCN~2026-11-10T10:25~Europe/Madrid}</pre>
 */
public final class GammaCsvFormat implements ProviderFormat {

    static final String HEADER = "REF,PRICE_EUR,SEGMENTS";

    private static final ProviderId PROVIDER = new ProviderId("mock-gamma");
    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm");
    private static final DateTimeFormatter UNPARSEABLE_TIME = DateTimeFormatter.ofPattern("dd/MM/uuuu H'h'");

    @Override
    public ProviderId providerId() {
        return PROVIDER;
    }

    @Override
    public MediaType mediaType() {
        return TEXT_CSV;
    }

    @Override
    public void write(Iterator<ProviderRecord> records, OutputStream out) throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write(HEADER);
        writer.write('\n');
        StringBuilder line = new StringBuilder(256);
        while (records.hasNext()) {
            line.setLength(0);
            appendRecord(line, records.next());
            writer.append(line).append('\n');
        }
        writer.flush(); // not closed: the caller owns the stream
    }

    private static void appendRecord(StringBuilder line, ProviderRecord record) {
        line.append("GAM-")
                .append(record.recordId())
                .append(',')
                .append(record.price().toPlainString())
                .append(',');
        int unparseable = record.unparseableTimeIndex();
        for (int i = 0; i < record.segments().size(); i++) {
            ScheduledSegment segment = record.segments().get(i);
            if (i > 0) {
                line.append('|');
            }
            line.append(segment.airline());
            appendZeroPadded(line, segment.flightNumber());
            endpoint(line, segment.origin(), segment.departure(), i == unparseable ? UNPARSEABLE_TIME : LOCAL_TIME);
            endpoint(line, segment.destination(), segment.arrival(), LOCAL_TIME);
        }
    }

    /** Flight number on 4 digits ({@code 7} → {@code 0007}); {@code String.format} is too slow per segment at 1M. */
    private static void appendZeroPadded(StringBuilder line, int flightNumber) {
        for (int limit = 1000; limit > 1 && flightNumber < limit; limit /= 10) {
            line.append('0');
        }
        line.append(flightNumber);
    }

    private static void endpoint(StringBuilder line, Airport airport, Instant time, DateTimeFormatter formatter) {
        line.append('~')
                .append(airport.code())
                .append('~')
                .append(time.atZone(airport.zone()).format(formatter))
                .append('~')
                .append(airport.zone().getId());
    }
}
