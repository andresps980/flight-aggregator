package com.andresps.flights.ingestion.infrastructure.adapter.out.provider.beta;

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
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Mock-Beta: nested XML ({@code response > itineraries > itinerary}, docs/providers.md), read with the StAX cursor API so
 * only one itinerary is held at a time.
 *
 * <p>XXE-safe: DTDs and external entities are disabled, and any {@code DOCTYPE} rejects the document before an entity
 * could be declared or resolved (spec 003, AC-8).
 */
public final class BetaXmlParser implements FeedParser {

    public static final ProviderId PROVIDER = new ProviderId("mock-beta");
    static final int MAX_TEXT_LENGTH = 64 * 1024;
    static final int MAX_SEGMENTS = 16;

    private static final XMLInputFactory FACTORY = secureFactory();

    static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    @Override
    public ProviderId provider() {
        return PROVIDER;
    }

    @Override
    public void parse(InputStream body, PartitionBuffer buffer) throws IOException {
        XMLStreamReader xml = null;
        try {
            xml = FACTORY.createXMLStreamReader(body);
            requireStart(nextTag(xml), xml, "response");
            while (nextTag(xml) == XMLStreamConstants.START_ELEMENT) {
                if ("itineraries".equals(xml.getLocalName())) {
                    readItineraries(xml, buffer);
                } else {
                    skipElement(xml);
                }
            }
            // after </response>: only whitespace, comments or processing instructions may follow
            while (xml.hasNext()) {
                rejectDoctype(xml.next());
            }
        } catch (XMLStreamException e) {
            IOException io = ioCause(e);
            if (io != null) {
                throw io;
            }
            throw new MalformedDocumentException("Mock-Beta feed is not valid XML: " + e.getMessage(), e);
        } finally {
            close(xml);
        }
    }

    private static void readItineraries(XMLStreamReader xml, PartitionBuffer buffer) throws XMLStreamException {
        while (nextTag(xml) == XMLStreamConstants.START_ELEMENT) {
            if ("itinerary".equals(xml.getLocalName())) {
                readItinerary(xml, buffer);
            } else {
                skipElement(xml);
            }
        }
    }

    /**
     * Reads one {@code itinerary} element to its end. Fields are checked as they appear (pricing, then each segment
     * when it ends); after the first failure the rest of the element is only consumed.
     */
    private static void readItinerary(XMLStreamReader xml, PartitionBuffer buffer) throws XMLStreamException {
        Itinerary itinerary = new Itinerary();
        int depth = 1;
        while (depth > 0) {
            int event = xml.next();
            rejectDoctype(event);
            if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                switch (xml.getLocalName()) {
                    case "totalAmount" -> {
                        String currency = xml.getAttributeValue(null, "currency");
                        String amount = readText(xml);
                        itinerary.check(() -> {
                            RecordValues.requireEur(currency);
                            itinerary.price = RecordValues.price(amount);
                        });
                    }
                    case "option" -> {
                        itinerary.options++;
                        depth++;
                    }
                    case "segment" -> {
                        RawSegment segment = readSegment(xml);
                        itinerary.check(() -> {
                            if (itinerary.segments.size() >= MAX_SEGMENTS) {
                                throw RecordValues.invalid("more than " + MAX_SEGMENTS + " segments");
                            }
                            itinerary.segments.add(segment.toSegment());
                        });
                    }
                    default -> depth++;
                }
            }
        }
        if (itinerary.failure != null) {
            buffer.addMalformed(itinerary.failure.reason());
            return;
        }
        try {
            buffer.addOffer(itinerary.toOffer());
        } catch (MalformedRecordException e) {
            buffer.addMalformed(e.reason());
        }
    }

    private static RawSegment readSegment(XMLStreamReader xml) throws XMLStreamException {
        RawSegment segment = new RawSegment();
        while (nextTag(xml) == XMLStreamConstants.START_ELEMENT) {
            switch (xml.getLocalName()) {
                case "carrier" -> segment.carrier = readText(xml);
                case "flightNumber" -> segment.number = readText(xml);
                case "departure" -> {
                    segment.from = xml.getAttributeValue(null, "airport");
                    segment.departure = xml.getAttributeValue(null, "dateTime");
                    skipElement(xml);
                }
                case "arrival" -> {
                    segment.to = xml.getAttributeValue(null, "airport");
                    segment.arrival = xml.getAttributeValue(null, "dateTime");
                    skipElement(xml);
                }
                default -> skipElement(xml);
            }
        }
        return segment;
    }

    /**
     * The text of a text-only element (positioned on its start tag), up to its end tag. Returns {@code null} if the
     * element contains child elements: treated as a missing value, so the record is an {@code INVALID_RECORD} and the
     * document is still fine; text longer than
     * {@link #MAX_TEXT_LENGTH} fails the document, so a broken feed can't grow memory without bound.
     */
    private static String readText(XMLStreamReader xml) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        boolean nested = false;
        int depth = 1;
        while (depth > 0) {
            int event = xml.next();
            rejectDoctype(event);
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    nested = true;
                    depth++;
                }
                case XMLStreamConstants.END_ELEMENT -> depth--;
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (!nested) {
                        text.append(xml.getTextCharacters(), xml.getTextStart(), xml.getTextLength());
                        if (text.length() > MAX_TEXT_LENGTH) {
                            throw new MalformedDocumentException(
                                    "Mock-Beta feed has a text longer than " + MAX_TEXT_LENGTH + " characters");
                        }
                    }
                }
                default -> {
                    // comments and processing instructions carry no text
                }
            }
        }
        return nested ? null : text.toString();
    }

    /** Like {@link XMLStreamReader#nextTag()}, but a DOCTYPE is a malformed document, never processed. */
    private static int nextTag(XMLStreamReader xml) throws XMLStreamException {
        while (true) {
            int event = xml.next();
            rejectDoctype(event);
            switch (event) {
                case XMLStreamConstants.START_ELEMENT, XMLStreamConstants.END_ELEMENT -> {
                    return event;
                }
                case XMLStreamConstants.END_DOCUMENT ->
                    throw new MalformedDocumentException("Mock-Beta feed ended before the closing </response>");
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (!xml.isWhiteSpace()) {
                        throw new MalformedDocumentException("Mock-Beta feed has unexpected text");
                    }
                }
                default -> {
                    // whitespace, comments and processing instructions are skipped
                }
            }
        }
    }

    /** Consumes the current element (positioned on its start tag) up to its end tag. */
    private static void skipElement(XMLStreamReader xml) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int event = xml.next();
            rejectDoctype(event);
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static void rejectDoctype(int event) {
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
            throw new MalformedDocumentException("Mock-Beta feed must not contain a DOCTYPE or entity references");
        }
    }

    private static void requireStart(int event, XMLStreamReader xml, String name) {
        if (event != XMLStreamConstants.START_ELEMENT || !name.equals(xml.getLocalName())) {
            throw new MalformedDocumentException("Mock-Beta feed must start with <" + name + ">");
        }
    }

    private static IOException ioCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException io) {
                return io;
            }
            if (t instanceof XMLStreamException stream && stream.getNestedException() instanceof IOException io) {
                return io;
            }
        }
        return null;
    }

    private static void close(XMLStreamReader xml) {
        if (xml == null) {
            return;
        }
        try {
            xml.close(); // does not close the underlying stream: the caller owns it
        } catch (XMLStreamException ignored) {
            // nothing to release beyond the parser itself
        }
    }

    /** One itinerary being read: the first failure wins, later fields are not checked. */
    private static final class Itinerary {

        private Money price;
        private int options;
        private final List<Segment> segments = new ArrayList<>();
        private MalformedRecordException failure;

        void check(Runnable step) {
            if (failure != null) {
                return;
            }
            try {
                step.run();
            } catch (MalformedRecordException e) {
                failure = e;
            }
        }

        FlightOffer toOffer() {
            if (options != 1) {
                throw RecordValues.invalid("expected exactly one option, got " + options);
            }
            return RecordValues.offer(segments, PROVIDER, price);
        }
    }

    /** Raw attributes and texts of one {@code segment}, checked in the documented order. */
    private static final class RawSegment {

        private String carrier;
        private String number;
        private String from;
        private String departure;
        private String to;
        private String arrival;

        Segment toSegment() {
            var flight = RecordValues.flightNumber(carrier, number);
            var origin = RecordValues.airport(from);
            var departs = RecordValues.offsetDateTime(departure);
            var destination = RecordValues.airport(to);
            var arrives = RecordValues.offsetDateTime(arrival);
            return RecordValues.segment(flight, origin, destination, departs, arrives);
        }
    }
}
