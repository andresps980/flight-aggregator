package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format;

import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormat;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import org.springframework.http.MediaType;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.json.JsonFactory;

/**
 * Mock-Alpha: a raw JSON array of flat-ish objects with legacy field names, epoch-millis UTC times and
 * carrier-prefixed flight numbers, streamed with Jackson's generator.
 *
 * <pre>{@code [{"offerRef":"ALP-20261110-000017-0","cxr":"IB","totFare":129.90,"cur":"EUR",
 *   "legs":[{"fltNum":"IB345","org":"MAD","dst":"BCN","depTs":1794297600000,"arrTs":1794302700000}]}]}</pre>
 */
public final class AlphaJsonFormat implements ProviderFormat {

    private static final ProviderId PROVIDER = new ProviderId("mock-alpha");
    private static final String UNPARSEABLE_TIME = "TBD";
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            // A feed interrupted by an exception must stay truncated (invalid JSON), not be closed into a valid array
            .disable(StreamWriteFeature.AUTO_CLOSE_CONTENT)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    @Override
    public ProviderId providerId() {
        return PROVIDER;
    }

    @Override
    public MediaType mediaType() {
        return MediaType.APPLICATION_JSON;
    }

    @Override
    public void write(Iterator<ProviderRecord> records, OutputStream out) throws IOException {
        try (JsonGenerator json = FACTORY.createGenerator(ObjectWriteContext.empty(), out)) {
            json.writeStartArray();
            while (records.hasNext()) {
                writeRecord(json, records.next());
            }
            json.writeEndArray();
        } catch (JacksonIOException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException("Mock-Alpha serialization failed", e);
        }
    }

    private static void writeRecord(JsonGenerator json, ProviderRecord record) {
        json.writeStartObject();
        json.writeStringProperty("offerRef", "ALP-" + record.recordId());
        json.writeStringProperty("cxr", record.segments().getFirst().airline());
        json.writeName("totFare");
        json.writeNumber(record.price());
        json.writeStringProperty("cur", record.currency().getCurrencyCode()); // spec 008: per record
        json.writeName("legs");
        json.writeStartArray();
        int unparseable = record.unparseableTimeIndex();
        for (int i = 0; i < record.segments().size(); i++) {
            ScheduledSegment segment = record.segments().get(i);
            json.writeStartObject();
            json.writeStringProperty("fltNum", segment.airline() + segment.flightNumber());
            json.writeStringProperty("org", segment.origin().code());
            json.writeStringProperty("dst", segment.destination().code());
            if (i == unparseable) {
                json.writeStringProperty("depTs", UNPARSEABLE_TIME);
            } else {
                json.writeNumberProperty("depTs", segment.departure().toEpochMilli());
            }
            json.writeNumberProperty("arrTs", segment.arrival().toEpochMilli());
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeEndObject();
    }
}
