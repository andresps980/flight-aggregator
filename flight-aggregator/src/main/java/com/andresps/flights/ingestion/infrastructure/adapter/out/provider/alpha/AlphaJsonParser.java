package com.andresps.flights.ingestion.infrastructure.adapter.out.provider.alpha;

import com.andresps.flights.aggregation.domain.model.AirportCode;
import com.andresps.flights.aggregation.domain.model.FlightNumber;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.FeedParser;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.MalformedDocumentException;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.MalformedRecordException;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.PartitionBuffer;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.RecordValues;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.json.JsonFactory;

/**
 * Mock-Alpha: a raw JSON array of flat-ish objects (docs/providers.md), read token by token with the Jackson 3 streaming
 * parser. Only one record's fields are held at a time. Each record's fields are captured as raw tokens in document
 * order and then checked in that order, so the reason recorded for a malformed record is its first failing field.
 */
public final class AlphaJsonParser implements FeedParser {

    public static final ProviderId PROVIDER = new ProviderId("mock-alpha");

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE) // the caller owns the body
            .build();

    @Override
    public ProviderId provider() {
        return PROVIDER;
    }

    @Override
    public void parse(InputStream body, PartitionBuffer buffer) throws IOException {
        try (JsonParser json = FACTORY.createParser(ObjectReadContext.empty(), body)) {
            JsonToken first = json.nextToken();
            if (first != JsonToken.START_ARRAY) {
                throw new MalformedDocumentException("Mock-Alpha feed must be a JSON array, got " + first);
            }
            for (JsonToken token = json.nextToken(); token != JsonToken.END_ARRAY; token = json.nextToken()) {
                if (token == null) {
                    throw new MalformedDocumentException("Mock-Alpha feed ended before the closing ']'");
                }
                readRecord(json, token, buffer);
            }
            if (json.nextToken() != null) {
                throw new MalformedDocumentException("Mock-Alpha feed has content after the closing ']'");
            }
        } catch (JacksonIOException e) {
            throw e.getCause();
        } catch (JacksonException e) {
            throw new MalformedDocumentException("Mock-Alpha feed is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static void readRecord(JsonParser json, JsonToken token, PartitionBuffer buffer) {
        if (token != JsonToken.START_OBJECT) {
            json.skipChildren();
            buffer.addMalformed(MalformedReason.INVALID_RECORD);
            return;
        }
        List<Field> fields = readObject(json, true);
        try {
            buffer.addOffer(toOffer(fields));
        } catch (MalformedRecordException e) {
            buffer.addMalformed(e.reason());
        }
    }

    /** Reads the current object's fields; nested values are kept only for {@code legs} (when {@code withLegs}). */
    private static List<Field> readObject(JsonParser json, boolean withLegs) {
        List<Field> fields = new ArrayList<>();
        JsonToken token = json.nextToken();
        for (; token == JsonToken.PROPERTY_NAME; token = json.nextToken()) {
            String name = json.currentName();
            JsonToken value = requirePresent(json.nextToken());
            if (withLegs && "legs".equals(name) && value == JsonToken.START_ARRAY) {
                fields.add(Field.legs(readLegs(json)));
            } else if (value.isStructStart()) {
                json.skipChildren();
                fields.add(new Field(name, value, null, null));
            } else {
                fields.add(new Field(name, value, json.getString(), null));
            }
        }
        if (token != JsonToken.END_OBJECT) {
            throw new MalformedDocumentException("Mock-Alpha feed ended inside a record");
        }
        return fields;
    }

    /** {@code null} entries stand for array elements that are not objects. */
    private static List<List<Field>> readLegs(JsonParser json) {
        List<List<Field>> legs = new ArrayList<>();
        for (JsonToken token = requirePresent(json.nextToken());
                token != JsonToken.END_ARRAY;
                token = requirePresent(json.nextToken())) {
            if (token == JsonToken.START_OBJECT) {
                legs.add(readObject(json, false));
            } else {
                json.skipChildren();
                legs.add(null);
            }
        }
        return legs;
    }

    private static com.andresps.flights.aggregation.domain.model.FlightOffer toOffer(List<Field> fields) {
        Money price = null;
        boolean eur = false;
        List<Segment> segments = null;
        for (Field field : fields) {
            switch (field.name()) {
                case "totFare" -> price = RecordValues.price(number(field));
                case "cur" -> {
                    RecordValues.requireEur(string(field));
                    eur = true;
                }
                case "legs" -> segments = toSegments(field);
                default -> {
                    // offerRef, cxr and unknown fields are not needed
                }
            }
        }
        if (!eur) {
            throw RecordValues.invalid("missing currency");
        }
        return RecordValues.offer(segments, PROVIDER, price);
    }

    private static List<Segment> toSegments(Field field) {
        if (field.legs() == null) {
            throw RecordValues.invalid("legs must be an array");
        }
        List<Segment> segments = new ArrayList<>(field.legs().size());
        for (List<Field> leg : field.legs()) {
            if (leg == null) {
                throw RecordValues.invalid("a leg must be an object");
            }
            segments.add(toSegment(leg));
        }
        return segments;
    }

    private static Segment toSegment(List<Field> leg) {
        FlightNumber flight = null;
        AirportCode origin = null;
        AirportCode destination = null;
        Instant departure = null;
        Instant arrival = null;
        for (Field field : leg) {
            switch (field.name()) {
                case "fltNum" -> flight = RecordValues.flightNumber(string(field));
                case "org" -> origin = RecordValues.airport(string(field));
                case "dst" -> destination = RecordValues.airport(string(field));
                case "depTs" -> departure = epochMillis(field);
                case "arrTs" -> arrival = epochMillis(field);
                default -> {
                    // unknown leg fields are ignored
                }
            }
        }
        if (flight == null || origin == null || destination == null || departure == null || arrival == null) {
            throw RecordValues.invalid("a leg is missing a field");
        }
        return RecordValues.segment(flight, origin, destination, departure, arrival);
    }

    private static JsonToken requirePresent(JsonToken token) {
        if (token == null) {
            throw new MalformedDocumentException("Mock-Alpha feed ended inside a record");
        }
        return token;
    }

    /** A string value; {@code null} (missing) for JSON null, numbers or structures. */
    private static String string(Field field) {
        return field.token() == JsonToken.VALUE_STRING ? field.text() : null;
    }

    private static BigDecimal number(Field field) {
        if (!field.token().isNumeric()) {
            throw RecordValues.invalid("totFare must be a JSON number, got " + field.token());
        }
        try {
            return new BigDecimal(field.text());
        } catch (NumberFormatException e) {
            throw RecordValues.invalid("totFare is not a number: " + field.text());
        }
    }

    /** Epoch milliseconds: an integer; a string ({@code "TBD"}) or a fraction is an unparseable time. */
    private static Instant epochMillis(Field field) {
        if (field.token() == JsonToken.VALUE_NULL || field.token().isStructStart()) {
            throw RecordValues.invalid("missing time");
        }
        if (field.token() != JsonToken.VALUE_NUMBER_INT) {
            throw RecordValues.unparseableTime(field.text());
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(field.text()));
        } catch (NumberFormatException e) {
            throw RecordValues.unparseableTime(field.text());
        }
    }

    /** One raw field: its token and text, or the legs when it is the {@code legs} array. */
    private record Field(String name, JsonToken token, String text, List<List<Field>> legs) {

        static Field legs(List<List<Field>> legs) {
            return new Field("legs", JsonToken.START_ARRAY, null, legs);
        }
    }
}
