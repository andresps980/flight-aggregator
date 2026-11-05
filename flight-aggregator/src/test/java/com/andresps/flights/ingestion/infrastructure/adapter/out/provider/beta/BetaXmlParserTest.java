package com.andresps.flights.ingestion.infrastructure.adapter.out.provider.beta;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ParserTestSupport.failingAfter;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ParserTestSupport.parse;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ParserTestSupport.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.model.AirportCode;
import com.andresps.flights.aggregation.domain.model.FlightNumber;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.MalformedDocumentException;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.PartitionBuffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Mock-Beta: nested XML, ISO-8601 times with the airport's offset, carrier and number split (docs/providers.md). */
class BetaXmlParserTest {

    private final BetaXmlParser parser = new BetaXmlParser();

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><response provider=\"mock-beta\">";

    private static String document(String... itineraries) {
        return HEAD + "<itineraries>" + String.join("", itineraries) + "</itineraries></response>";
    }

    private static String segment(
            String carrier, String number, String from, String departure, String to, String arrival) {
        return "<segment><carrier>%s</carrier><flightNumber>%s</flightNumber>".formatted(carrier, number)
                + "<departure airport=\"%s\" dateTime=\"%s\"/>".formatted(from, departure)
                + "<arrival airport=\"%s\" dateTime=\"%s\"/></segment>".formatted(to, arrival);
    }

    private static String itinerary(String amount, String... segments) {
        return "<itinerary id=\"BET-1\"><pricing><totalAmount currency=\"EUR\">%s</totalAmount></pricing>"
                        .formatted(amount)
                + "<originDestinationOptions><option><segments>" + String.join("", segments)
                + "</segments></option></originDestinationOptions></itinerary>";
    }

    /** The providers.md example: VY1 DUB → EZE + VY8227 EZE → FRA. */
    private static String vy1DubEzeFra() {
        return itinerary(
                "428.41",
                segment("VY", "1", "DUB", "2026-11-06T03:05:00+00:00", "EZE", "2026-11-06T10:40:00-03:00"),
                segment("VY", "8227", "EZE", "2026-11-06T15:25:00-03:00", "FRA", "2026-11-07T00:55:00+01:00"));
    }

    private static String valid() {
        return itinerary(
                "131.40", segment("IB", "345", "MAD", "2026-11-10T09:00:00+01:00", "BCN", "2026-11-10T10:25:00+01:00"));
    }

    @Test
    @DisplayName("AC-1: an itinerary element becomes a FlightOffer with offsets converted to UTC instants")
    void parsesValidItinerary() {
        PartitionBuffer buffer = parse(parser, document(vy1DubEzeFra()));

        assertThat(buffer.offers()).hasSize(1);
        FlightOffer offer = buffer.offers().getFirst();
        assertThat(offer.provider()).isEqualTo(new ProviderId("mock-beta"));
        assertThat(offer.basePrice()).isEqualTo(Money.eur("428.41"));
        assertThat(offer.itinerary().segments())
                .containsExactly(
                        new Segment(
                                FlightNumber.parse("VY1"),
                                new AirportCode("DUB"),
                                new AirportCode("EZE"),
                                utc("2026-11-06T03:05:00Z"),
                                utc("2026-11-06T13:40:00Z")),
                        new Segment(
                                FlightNumber.parse("VY8227"),
                                new AirportCode("EZE"),
                                new AirportCode("FRA"),
                                utc("2026-11-06T18:25:00Z"),
                                utc("2026-11-06T23:55:00Z")));
    }

    @Test
    @DisplayName("AC-1: whitespace, comments and unknown elements are tolerated")
    void toleratesIndentationAndUnknownElements() {
        String indented = document("\n  <!-- one itinerary -->\n  "
                + valid().replace("<pricing>", "<notes><n>x</n></notes>\n    <pricing>") + "\n");

        assertThat(parse(parser, indented).offers()).hasSize(1);
    }

    @Test
    @DisplayName("AC-3: carrier + zero-padded number is the canonical flight number")
    void normalisesFlightNumber() {
        String padded = itinerary(
                "99.00", segment("VY", "0001", "DUB", "2026-11-06T03:05:00+00:00", "EZE", "2026-11-06T10:40:00-03:00"));

        PartitionBuffer buffer = parse(parser, document(padded));

        assertThat(buffer.offers().getFirst().itinerary().segments().getFirst().flightNumber())
                .isEqualTo(FlightNumber.parse("VY1"));
    }

    @Test
    @DisplayName("AC-4: an empty itineraries element is a successful partition with zero offers")
    void emptyFeed() {
        assertThat(parse(parser, HEAD + "<itineraries/></response>").recordsReceived())
                .isZero();
        assertThat(parse(parser, document()).recordsReceived()).isZero();
    }

    @Test
    @DisplayName("AC-5: records reach the buffer while the body is read, before the stream fails")
    void streamsRecords() {
        PartitionBuffer buffer = new PartitionBuffer();

        assertThatThrownBy(() -> parser.parse(failingAfter(HEAD + "<itineraries>" + valid() + valid()), buffer))
                .isInstanceOf(IOException.class);
        assertThat(buffer.offers()).hasSize(2);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "bad airport code      | airport=\"MAD\"                    | airport=\"M4D\"                    | BAD_AIRPORT_CODE",
                "negative price        | >131.40<                         | >-12.50<                          | NON_POSITIVE_PRICE",
                "zero price            | >131.40<                         | >0.00<                            | NON_POSITIVE_PRICE",
                "impossible time       | 2026-11-10T09:00:00+01:00        | 2026-11-10T25:61:00+01:00         | UNPARSEABLE_TIME",
                "time without offset   | 2026-11-10T09:00:00+01:00        | 2026-11-10T09:00:00               | UNPARSEABLE_TIME",
                "non-numeric flight    | <flightNumber>345<               | <flightNumber>3A5<                | BAD_FLIGHT_NUMBER",
                "bad carrier           | <carrier>IB<                     | <carrier>I<                       | BAD_FLIGHT_NUMBER",
                "non-numeric price     | >131.40<                         | >abc<                             | INVALID_RECORD",
                "non-EUR currency      | currency=\"EUR\"                   | currency=\"USD\"                   | INVALID_RECORD",
                "missing currency      | ' currency=\"EUR\"'                | ''                                | INVALID_RECORD",
                "missing departure time | ' dateTime=\"2026-11-10T09:00:00+01:00\"' | ''                       | INVALID_RECORD",
                "missing carrier       | <carrier>IB</carrier>            | ''                                | INVALID_RECORD",
                "same origin and dest  | airport=\"BCN\"                    | airport=\"MAD\"                    | INVALID_RECORD",
            })
    @DisplayName("AC-6: a malformed itinerary is counted under its reason and skipped; the next one is still parsed")
    void countsMalformedRecords(String name, String from, String to, MalformedReason reason) {
        String broken =
                valid().replaceFirst(java.util.regex.Pattern.quote(from), java.util.regex.Matcher.quoteReplacement(to));

        PartitionBuffer buffer = parse(parser, document(broken, valid()));

        assertThat(buffer.malformed()).as(name).containsOnlyKeys(reason);
        assertThat(buffer.malformed(reason)).as(name).isEqualTo(1);
        assertThat(buffer.offers()).as(name).hasSize(1);
        assertThat(buffer.recordsReceived()).as(name).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-6: an itinerary without segments is an INVALID_RECORD")
    void noSegments() {
        PartitionBuffer buffer = parse(parser, document(itinerary("99.00"), valid()));

        assertThat(buffer.malformed()).containsOnlyKeys(MalformedReason.INVALID_RECORD);
        assertThat(buffer.offers()).hasSize(1);
    }

    @Test
    @DisplayName(
            "AC-6: an element with an unexpected child where text is expected is one INVALID_RECORD, not a broken feed")
    void nestedElementInTextField() {
        String nested = valid().replace("<carrier>IB</carrier>", "<carrier>IB<x/></carrier>");
        String nestedAmount = valid().replace(">131.40<", "><amount>131.40</amount><");

        PartitionBuffer buffer = parse(parser, document(valid(), nested, nestedAmount, valid()));

        assertThat(buffer.malformed())
                .containsOnly(org.assertj.core.api.Assertions.entry(MalformedReason.INVALID_RECORD, 2L));
        assertThat(buffer.offers()).hasSize(2);
    }

    @Test
    @DisplayName("AC-6: more than 16 segments in one itinerary is an INVALID_RECORD (bounded memory)")
    void tooManySegments() {
        String segment = segment("IB", "345", "MAD", "2026-11-10T09:00:00+01:00", "BCN", "2026-11-10T10:25:00+01:00");
        String huge =
                itinerary("99.00", java.util.Collections.nCopies(17, segment).toArray(String[]::new));

        PartitionBuffer buffer = parse(parser, document(huge, valid()));

        assertThat(buffer.malformed()).containsOnlyKeys(MalformedReason.INVALID_RECORD);
        assertThat(buffer.offers()).hasSize(1);
    }

    @Test
    @DisplayName("AC-5/AC-7: a text longer than 64 KiB fails the document instead of growing memory without bound")
    void rejectsHugeText() {
        String huge = valid().replace("<carrier>IB</carrier>", "<carrier>" + "I".repeat(70_000) + "</carrier>");

        assertThatThrownBy(() -> parse(parser, document(huge))).isInstanceOf(MalformedDocumentException.class);
    }

    @Test
    @DisplayName("AC-6: one reason per record, the first failing field in document order")
    void firstFailingFieldWins() {
        String twoFaults = valid().replace(">131.40<", ">-1.00<").replace("airport=\"MAD\"", "airport=\"M4D\"");

        assertThat(parse(parser, document(twoFaults)).malformed()).containsOnlyKeys(MalformedReason.NON_POSITIVE_PRICE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "cut before the end     | %s",
                "cut inside an element  | %s<itinerary id=\"BET-2\"><pricing><totalAm",
                "unclosed element       | %s<itinerary></itineraries></response>",
                "not XML                | {\"itineraries\":[]}",
                "empty body             | ''",
            })
    @DisplayName("AC-7: a broken or truncated document fails as MALFORMED_DOCUMENT")
    void rejectsBrokenDocument(String name, String template) {
        String body = template.contains("%s") ? template.formatted(HEAD + "<itineraries>" + valid()) : template;

        assertThatThrownBy(() -> parse(parser, body)).as(name).isInstanceOf(MalformedDocumentException.class);
    }

    @Test
    @DisplayName("AC-7: an unexpected root element fails as MALFORMED_DOCUMENT")
    void rejectsWrongRoot() {
        String body = "<?xml version=\"1.0\"?><flights><itineraries>" + valid() + "</itineraries></flights>";

        assertThatThrownBy(() -> parse(parser, body)).isInstanceOf(MalformedDocumentException.class);
    }

    @Test
    @DisplayName("AC-8 (security): an external entity (XXE) is rejected and the file is never read")
    void rejectsExternalEntity(@TempDir Path dir) throws IOException {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOPSECRET");
        String body = "<?xml version=\"1.0\"?><!DOCTYPE response [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>"
                + "<response><itineraries>"
                + valid().replace("<carrier>IB</carrier>", "<carrier>&xxe;</carrier>") + "</itineraries></response>";

        assertThatThrownBy(() -> parse(parser, body))
                .isInstanceOf(MalformedDocumentException.class)
                .satisfies(e -> assertThat(messages(e)).doesNotContain("TOPSECRET"));
    }

    @Test
    @DisplayName("AC-8 (security): internal entity expansion (billion laughs) is rejected")
    void rejectsEntityExpansion() {
        String body = "<?xml version=\"1.0\"?><!DOCTYPE response [<!ENTITY a \"aaaaaaaaaa\">"
                + "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">]>"
                + "<response><itineraries>" + valid().replace("<carrier>IB</carrier>", "<carrier>&c;</carrier>")
                + "</itineraries></response>";

        assertThatThrownBy(() -> parse(parser, body)).isInstanceOf(MalformedDocumentException.class);
    }

    @Test
    @DisplayName("AC-8 (security): any DOCTYPE is rejected, even one that declares nothing")
    void rejectsDoctype() {
        String body = "<?xml version=\"1.0\"?><!DOCTYPE response>" + "<response>" + "<itineraries>" + valid()
                + "</itineraries></response>";

        assertThatThrownBy(() -> parse(parser, body)).isInstanceOf(MalformedDocumentException.class);
    }

    private static String messages(Throwable e) {
        var all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
