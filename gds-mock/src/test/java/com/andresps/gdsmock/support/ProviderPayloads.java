package com.andresps.gdsmock.support;

import com.andresps.gdsmock.inventory.domain.model.MalformationKind;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Independent, client-side parsers for the three provider formats (as the aggregator's adapters will need them),
 * plus the expected parsed view of a generated record. Used to prove that every serializer's output parses back to
 * the generated data.
 */
public final class ProviderPayloads {

    public static final String ALPHA_PREFIX = "ALP-";
    public static final String BETA_PREFIX = "BET-";
    public static final String GAMMA_PREFIX = "GAM-";
    public static final String GAMMA_HEADER = "REF,PRICE_EUR,SEGMENTS";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private ProviderPayloads() {}

    /** Mock-Alpha: raw JSON array, epoch-millis times, carrier-prefixed flight numbers. */
    public static List<ParsedRecord> parseAlpha(byte[] body) {
        JsonNode root = JSON.readTree(body);
        if (!root.isArray()) {
            throw new AssertionError("Mock-Alpha body is not a JSON array");
        }
        List<ParsedRecord> records = new ArrayList<>();
        for (JsonNode node : root) {
            List<TestSegment> segments = new ArrayList<>();
            for (JsonNode leg : node.get("legs")) {
                String flight = normaliseFlight(leg.get("fltNum").asString());
                segments.add(new TestSegment(
                        flight,
                        flight.substring(0, 2),
                        leg.get("org").asString(),
                        leg.get("dst").asString(),
                        epochMillis(leg.get("depTs")),
                        epochMillis(leg.get("arrTs"))));
            }
            JsonNode fare = node.get("totFare");
            records.add(new ParsedRecord(
                    node.get("offerRef").asString(), fare.decimalValue().toPlainString(), segments));
        }
        return records;
    }

    /** Mock-Beta: nested XML, ISO-8601 times with offset, carrier and number in separate elements. */
    public static List<ParsedRecord> parseBeta(byte[] body) {
        Document document = parseXml(body);
        List<ParsedRecord> records = new ArrayList<>();
        NodeList itineraries = document.getElementsByTagName("itinerary");
        for (int i = 0; i < itineraries.getLength(); i++) {
            Element itinerary = (Element) itineraries.item(i);
            Element amount = child(child(itinerary, "pricing"), "totalAmount");
            List<TestSegment> segments = new ArrayList<>();
            NodeList segmentNodes = itinerary.getElementsByTagName("segment");
            for (int s = 0; s < segmentNodes.getLength(); s++) {
                Element segment = (Element) segmentNodes.item(s);
                String carrier = child(segment, "carrier").getTextContent();
                String flight = carrier
                        + Integer.parseInt(child(segment, "flightNumber").getTextContent());
                Element departure = child(segment, "departure");
                Element arrival = child(segment, "arrival");
                segments.add(new TestSegment(
                        flight,
                        carrier,
                        departure.getAttribute("airport"),
                        arrival.getAttribute("airport"),
                        offsetDateTime(departure.getAttribute("dateTime")),
                        offsetDateTime(arrival.getAttribute("dateTime"))));
            }
            records.add(new ParsedRecord(itinerary.getAttribute("id"), amount.getTextContent(), segments));
        }
        return records;
    }

    /** Mock-Gamma: header + one line per itinerary, segments {@code |}-separated, sub-fields {@code ~}-separated. */
    public static List<ParsedRecord> parseGamma(byte[] body) {
        String[] lines = new String(body, StandardCharsets.UTF_8).split("\n");
        if (!lines[0].equals(GAMMA_HEADER)) {
            throw new AssertionError("Unexpected Mock-Gamma header: " + lines[0]);
        }
        List<ParsedRecord> records = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split(",", 3);
            List<TestSegment> segments = new ArrayList<>();
            for (String segment : fields[2].split("\\|")) {
                String[] parts = segment.split("~", -1);
                if (parts.length != 7) {
                    throw new AssertionError("Mock-Gamma segment must have 7 sub-fields: " + segment);
                }
                String flight = normaliseFlight(parts[0]);
                segments.add(new TestSegment(
                        flight,
                        flight.substring(0, 2),
                        parts[1],
                        parts[4],
                        localDateTime(parts[2], parts[3]),
                        localDateTime(parts[5], parts[6])));
            }
            records.add(new ParsedRecord(fields[0], fields[1], segments));
        }
        return records;
    }

    /** What a client should parse from a generated record, given the provider's record-id prefix. */
    public static ParsedRecord expected(ProviderRecord record, String prefix) {
        int unparseableIndex = unparseableIndex(record);
        List<TestSegment> segments = new ArrayList<>();
        for (int i = 0; i < record.segments().size(); i++) {
            ScheduledSegment s = record.segments().get(i);
            segments.add(new TestSegment(
                    s.airline() + s.flightNumber(),
                    s.airline(),
                    s.origin().code(),
                    s.destination().code(),
                    i == unparseableIndex ? null : s.departure(),
                    s.arrival()));
        }
        return new ParsedRecord(prefix + record.recordId(), record.price().toPlainString(), segments);
    }

    /** Converts a generated segment list to the neutral test view. */
    public static List<TestSegment> toTestSegments(List<ScheduledSegment> segments) {
        return segments.stream()
                .map(s -> new TestSegment(
                        s.airline() + s.flightNumber(),
                        s.airline(),
                        s.origin().code(),
                        s.destination().code(),
                        s.departure(),
                        s.arrival()))
                .toList();
    }

    private static int unparseableIndex(ProviderRecord record) {
        return record.malformation()
                .filter(m -> m.kind() == MalformationKind.UNPARSEABLE_TIME)
                .map(m -> m.segmentIndex())
                .orElse(-1);
    }

    private static String normaliseFlight(String raw) {
        return raw.substring(0, 2) + Integer.parseInt(raw.substring(2));
    }

    private static Instant epochMillis(JsonNode node) {
        return node.isNumber() ? Instant.ofEpochMilli(node.longValue()) : null;
    }

    private static Instant offsetDateTime(String raw) {
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant localDateTime(String local, String zone) {
        try {
            return LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Document parseXml(byte[] body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new AssertionError("Mock-Beta body is not well-formed XML", e);
        }
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getElementsByTagName(name);
        if (children.getLength() == 0) {
            throw new AssertionError("Missing <" + name + "> in <" + parent.getTagName() + ">");
        }
        return (Element) children.item(0);
    }
}
