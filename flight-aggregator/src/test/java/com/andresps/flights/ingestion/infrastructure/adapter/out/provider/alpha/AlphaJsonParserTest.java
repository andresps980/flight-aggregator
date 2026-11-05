package com.andresps.flights.ingestion.infrastructure.adapter.out.provider.alpha;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Mock-Alpha: raw JSON array, epoch-millisecond times, carrier-prefixed flight numbers (docs/providers.md). */
class AlphaJsonParserTest {

    private final AlphaJsonParser parser = new AlphaJsonParser();

    /** The providers.md example: VY1 DUB → EZE + VY8227 EZE → FRA. */
    private static final String VY1_DUB_EZE_FRA = """
            {"offerRef":"ALP-20261106-000001-0","cxr":"VY","totFare":433.51,"cur":"EUR",
             "legs":[{"fltNum":"VY1","org":"DUB","dst":"EZE","depTs":1793934300000,"arrTs":1793972400000},
                     {"fltNum":"VY8227","org":"EZE","dst":"FRA","depTs":1793989500000,"arrTs":1794009300000}]}""";

    private static String direct(String fltNum, String org, String totFare, String depTs) {
        return """
                {"offerRef":"ALP-1","cxr":"IB","totFare":%s,"cur":"EUR",
                 "legs":[{"fltNum":%s,"org":%s,"dst":"BCN","depTs":%s,"arrTs":1794302700000}]}""".formatted(totFare, fltNum, org, depTs);
    }

    private static String valid() {
        return direct("\"IB345\"", "\"MAD\"", "129.90", "1794297600000");
    }

    @Test
    @DisplayName("AC-1: a record becomes a FlightOffer with ordered segments, airports, EUR price and UTC instants")
    void parsesValidRecord() {
        PartitionBuffer buffer = parse(parser, "[" + VY1_DUB_EZE_FRA + "]");

        assertThat(buffer.offers()).hasSize(1);
        FlightOffer offer = buffer.offers().getFirst();
        assertThat(offer.provider()).isEqualTo(new ProviderId("mock-alpha"));
        assertThat(offer.basePrice()).isEqualTo(Money.eur("433.51"));
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
        assertThat(buffer.malformed()).isEmpty();
    }

    @Test
    @DisplayName("AC-1: unknown fields are skipped, whatever their shape")
    void skipsUnknownFields() {
        String record = valid().replace("\"cxr\":\"IB\"", "\"cxr\":\"IB\",\"extra\":{\"a\":[1,2,{\"b\":null}]}");

        assertThat(parse(parser, "[" + record + "]").offers()).hasSize(1);
    }

    @Test
    @DisplayName("AC-3: a zero-padded flight number is the same flight as the plain one")
    void normalisesFlightNumber() {
        PartitionBuffer buffer = parse(parser, "[" + direct("\"IB0345\"", "\"MAD\"", "129.90", "1794297600000") + "]");

        assertThat(buffer.offers().getFirst().itinerary().segments().getFirst().flightNumber())
                .isEqualTo(FlightNumber.parse("IB345"));
    }

    @Test
    @DisplayName("AC-4: an empty array is a successful partition with zero offers")
    void emptyFeed() {
        PartitionBuffer buffer = parse(parser, "[]");

        assertThat(buffer.offers()).isEmpty();
        assertThat(buffer.recordsReceived()).isZero();
    }

    @Test
    @DisplayName("AC-5: records reach the buffer while the body is read, before the stream fails")
    void streamsRecords() {
        PartitionBuffer buffer = new PartitionBuffer();

        assertThatThrownBy(() -> parser.parse(failingAfter("[" + valid() + "," + valid() + ","), buffer))
                .isInstanceOf(IOException.class);
        assertThat(buffer.offers()).hasSize(2);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "bad airport code         | '\"IB345\"' | '\"M4D\"' | 129.90  | 1794297600000 | BAD_AIRPORT_CODE",
                "zero price               | '\"IB345\"' | '\"MAD\"' | 0.00    | 1794297600000 | NON_POSITIVE_PRICE",
                "negative price           | '\"IB345\"' | '\"MAD\"' | -12.50  | 1794297600000 | NON_POSITIVE_PRICE",
                "string in a numeric time | '\"IB345\"' | '\"MAD\"' | 129.90  | '\"TBD\"'     | UNPARSEABLE_TIME",
                "fractional time          | '\"IB345\"' | '\"MAD\"' | 129.90  | 1.5           | UNPARSEABLE_TIME",
                "non-numeric flight       | '\"IBX45\"' | '\"MAD\"' | 129.90  | 1794297600000 | BAD_FLIGHT_NUMBER",
                "too long flight          | '\"IB12345\"' | '\"MAD\"' | 129.90 | 1794297600000 | BAD_FLIGHT_NUMBER",
                "price as a string        | '\"IB345\"' | '\"MAD\"' | '\"12\"' | 1794297600000 | INVALID_RECORD",
                "huge price               | '\"IB345\"' | '\"MAD\"' | 1E999999 | 1794297600000 | INVALID_RECORD",
                "null airport             | '\"IB345\"' | null      | 129.90  | 1794297600000 | INVALID_RECORD",
            })
    @DisplayName("AC-6: a malformed record is counted under its reason and skipped; the next record is still parsed")
    void countsMalformedRecords(
            String name, String fltNum, String org, String totFare, String depTs, MalformedReason reason) {
        PartitionBuffer buffer = parse(parser, "[" + direct(fltNum, org, totFare, depTs) + "," + valid() + "]");

        assertThat(buffer.malformed()).containsOnlyKeys(reason);
        assertThat(buffer.malformed(reason)).isEqualTo(1);
        assertThat(buffer.offers()).hasSize(1);
        assertThat(buffer.recordsReceived()).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "non-EUR currency  | \"cur\":\"EUR\"            | \"cur\":\"USD\"",
                "missing currency  | ,\"cur\":\"EUR\"           | ''",
                "missing legs      | '\"legs\":[{'             | '\"nolegs\":[{'",
                "empty legs        | '\"legs\":[{\"fltNum\":\"IB345\",\"org\":\"MAD\",\"dst\":\"BCN\",\"depTs\":1794297600000,\"arrTs\":1794302700000}]' | '\"legs\":[]'",
                "missing arrival   | ,\"arrTs\":1794302700000  | ''",
                "same origin/dest  | \"dst\":\"BCN\"            | \"dst\":\"MAD\"",
                "legs not an array | '\"legs\":[{\"fltNum\":\"IB345\",\"org\":\"MAD\",\"dst\":\"BCN\",\"depTs\":1794297600000,\"arrTs\":1794302700000}]' | '\"legs\":\"none\"'",
            })
    @DisplayName("AC-6: any other unusable record counts as INVALID_RECORD and does not abort the feed")
    void countsInvalidRecords(String name, String from, String to) {
        String broken = valid().replace(from, to);

        PartitionBuffer buffer = parse(parser, "[" + broken + "," + valid() + "]");

        assertThat(buffer.malformed()).containsOnlyKeys(MalformedReason.INVALID_RECORD);
        assertThat(buffer.offers()).hasSize(1);
    }

    @Test
    @DisplayName("AC-6: an array element that is not an object is an INVALID_RECORD")
    void nonObjectElement() {
        PartitionBuffer buffer = parse(parser, "[42," + valid() + "]");

        assertThat(buffer.malformed(MalformedReason.INVALID_RECORD)).isEqualTo(1);
        assertThat(buffer.offers()).hasSize(1);
    }

    @Test
    @DisplayName("AC-6: one reason per record, the first failing field in document order")
    void firstFailingFieldWins() {
        String twoFaults = direct("\"IB345\"", "\"M4D\"", "-1.00", "1794297600000");

        PartitionBuffer buffer = parse(parser, "[" + twoFaults + "]");

        assertThat(buffer.malformed()).containsOnlyKeys(MalformedReason.NON_POSITIVE_PRICE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "missing closing bracket | '[%s'",
                "cut inside a record     | '[%s,{\"offerRef\":\"ALP-2\",\"totF'",
                "syntax error            | '[%s,}'",
                "root is an object       | '{\"items\":[%s]}'",
                "empty body              | ''",
                "trailing garbage        | '[%s] x'",
            })
    @DisplayName("AC-7: a broken or truncated document fails as MALFORMED_DOCUMENT")
    void rejectsBrokenDocument(String name, String template) {
        String body = template.formatted(valid());

        assertThatThrownBy(() -> parse(parser, body)).isInstanceOf(MalformedDocumentException.class);
    }
}
