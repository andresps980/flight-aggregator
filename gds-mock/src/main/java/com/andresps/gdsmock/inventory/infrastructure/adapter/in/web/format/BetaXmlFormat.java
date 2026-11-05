package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format;

import com.andresps.gdsmock.inventory.domain.model.Airport;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormat;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.http.MediaType;

/**
 * Mock-Beta: deeply nested XML written with StAX, times as ISO-8601 with the airport's UTC offset, carrier and flight
 * number in separate elements.
 *
 * <pre>{@code <response provider="mock-beta"><itineraries><itinerary id="BET-...">
 *   <pricing><totalAmount currency="EUR">131.40</totalAmount></pricing>
 *   <originDestinationOptions><option><segments><segment>
 *     <carrier>IB</carrier><flightNumber>345</flightNumber>
 *     <departure airport="MAD" dateTime="2026-11-10T09:00:00+01:00"/>
 *     <arrival airport="BCN" dateTime="2026-11-10T10:25:00+01:00"/>
 *   </segment></segments></option></originDestinationOptions>
 * </itinerary></itineraries></response>}</pre>
 */
public final class BetaXmlFormat implements ProviderFormat {

    private static final ProviderId PROVIDER = new ProviderId("mock-beta");
    /** {@code xxx} prints {@code +00:00} rather than {@code Z} for a zero offset. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    private static final DateTimeFormatter UNPARSEABLE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T25:61:00'xxx");
    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newFactory();

    @Override
    public ProviderId providerId() {
        return PROVIDER;
    }

    @Override
    public MediaType mediaType() {
        return MediaType.APPLICATION_XML;
    }

    @Override
    public void write(Iterator<ProviderRecord> records, OutputStream out) throws IOException {
        try {
            XMLStreamWriter xml = FACTORY.createXMLStreamWriter(out, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("response");
            xml.writeAttribute("provider", PROVIDER.value());
            xml.writeStartElement("itineraries");
            while (records.hasNext()) {
                writeRecord(xml, records.next());
            }
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            xml.close(); // StAX does not close the underlying stream
        } catch (XMLStreamException e) {
            // The JDK writer wraps output failures; anything else is a bug in this serializer, not a disconnect.
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException("Mock-Beta serialization failed", e);
        }
    }

    private static void writeRecord(XMLStreamWriter xml, ProviderRecord record) throws XMLStreamException {
        xml.writeStartElement("itinerary");
        xml.writeAttribute("id", "BET-" + record.recordId());
        xml.writeStartElement("pricing");
        xml.writeStartElement("totalAmount");
        xml.writeAttribute("currency", "EUR");
        xml.writeCharacters(record.price().toPlainString());
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeStartElement("originDestinationOptions");
        xml.writeStartElement("option");
        xml.writeStartElement("segments");
        int unparseable = record.unparseableTimeIndex();
        for (int i = 0; i < record.segments().size(); i++) {
            ScheduledSegment segment = record.segments().get(i);
            xml.writeStartElement("segment");
            element(xml, "carrier", segment.airline());
            element(xml, "flightNumber", String.valueOf(segment.flightNumber()));
            endpoint(
                    xml,
                    "departure",
                    segment.origin(),
                    segment.departure(),
                    i == unparseable ? UNPARSEABLE_TIME : TIME);
            endpoint(xml, "arrival", segment.destination(), segment.arrival(), TIME);
            xml.writeEndElement();
        }
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void element(XMLStreamWriter xml, String name, String text) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(text);
        xml.writeEndElement();
    }

    private static void endpoint(
            XMLStreamWriter xml, String name, Airport airport, Instant time, DateTimeFormatter formatter)
            throws XMLStreamException {
        xml.writeEmptyElement(name);
        xml.writeAttribute("airport", airport.code());
        xml.writeAttribute("dateTime", time.atZone(airport.zone()).format(formatter));
    }
}
