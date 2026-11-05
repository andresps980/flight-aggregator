package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.inventory.domain.model.MalformationKind;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormat;
import com.andresps.gdsmock.support.ParsedRecord;
import com.andresps.gdsmock.support.ProviderPayloads;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.w3c.dom.Document;

class BetaXmlFormatTest extends ProviderFormatContractTest {

    private static final String SEGMENT_PATH =
            "/response/itineraries/itinerary/originDestinationOptions/option" + "/segments/segment";

    private final BetaXmlFormat format = new BetaXmlFormat();

    @Override
    ProviderFormat format() {
        return format;
    }

    @Override
    List<ParsedRecord> parse(byte[] body) {
        return ProviderPayloads.parseBeta(body);
    }

    @Override
    String prefix() {
        return ProviderPayloads.BETA_PREFIX;
    }

    @Test
    @DisplayName("AC-9: Mock-Beta is served as application/xml under the id mock-beta")
    void identity() {
        assertThat(format.providerId().value()).isEqualTo("mock-beta");
        assertThat(format.mediaType()).isEqualTo(MediaType.APPLICATION_XML);
    }

    @Test
    @DisplayName("AC-9: deeply nested structure: response > itineraries > itinerary > pricing / options > segments")
    void nestedStructure() throws Exception {
        Document document = document(write(List.of(direct(), connection())));
        XPath xpath = XPathFactory.newInstance().newXPath();

        assertThat(xpath.evaluate("/response/@provider", document)).isEqualTo("mock-beta");
        assertThat(xpath.evaluate("count(/response/itineraries/itinerary)", document, XPathConstants.NUMBER))
                .isEqualTo(2.0);
        assertThat(xpath.evaluate("/response/itineraries/itinerary[1]/@id", document))
                .isEqualTo("BET-20261110-000017-0");
        assertThat(xpath.evaluate("/response/itineraries/itinerary[1]/pricing/totalAmount", document))
                .isEqualTo("129.90");
        assertThat(xpath.evaluate("/response/itineraries/itinerary[1]/pricing/totalAmount/@currency", document))
                .isEqualTo("EUR");
        assertThat(xpath.evaluate("count(" + SEGMENT_PATH + ")", document, XPathConstants.NUMBER))
                .isEqualTo(3.0);
        assertThat(xpath.evaluate(SEGMENT_PATH + "[1]/carrier", document)).isEqualTo("IB");
        assertThat(xpath.evaluate(SEGMENT_PATH + "[1]/flightNumber", document)).isEqualTo("345");
    }

    @Test
    @DisplayName("AC-9: times are ISO-8601 with the airport's offset (+01:00 Madrid, +00:00 London, -05:00 New York)")
    void isoTimesWithOffset() throws Exception {
        Document document = document(write(List.of(direct(), connection())));
        XPath xpath = XPathFactory.newInstance().newXPath();

        assertThat(xpath.evaluate("(//segment)[1]/departure/@dateTime", document))
                .isEqualTo("2026-11-10T09:00:00+01:00");
        assertThat(xpath.evaluate("(//segment)[1]/arrival/@dateTime", document)).isEqualTo("2026-11-10T10:25:00+01:00");
        assertThat(xpath.evaluate("(//segment)[2]/departure/@dateTime", document))
                .isEqualTo("2026-11-10T10:00:00+00:00");
        assertThat(xpath.evaluate("(//segment)[2]/arrival/@dateTime", document)).isEqualTo("2026-11-10T13:00:00-05:00");
        assertThat(xpath.evaluate("(//segment)[2]/departure/@airport", document))
                .isEqualTo("LHR");
    }

    @Test
    @DisplayName("AC-7: an unparseable time is an impossible clock time with a valid-looking offset")
    void unparseableTime() {
        String body = new String(write(List.of(malformed(MalformationKind.UNPARSEABLE_TIME))), StandardCharsets.UTF_8);

        assertThat(body).contains("T25:61:00");
    }

    private static Document document(byte[] body) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(body));
    }
}
