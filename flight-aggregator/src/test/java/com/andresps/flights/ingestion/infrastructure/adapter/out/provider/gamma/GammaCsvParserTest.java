package com.andresps.flights.ingestion.infrastructure.adapter.out.provider.gamma;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ParserTestSupport.failingAfter;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ParserTestSupport.parse;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ParserTestSupport.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.model.AirportCode;
import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.FlightNumber;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.MalformedDocumentException;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.PartitionBuffer;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Mock-Gamma: CSV lines, local date-time + IANA zone, zero-padded flight numbers (docs/providers.md). */
class GammaCsvParserTest {

    private final GammaCsvParser parser = new GammaCsvParser();

    private static final String HEADER = "REF,PRICE,CURRENCY,SEGMENTS\n";
    private static final String VALID =
            "GAM-1,127.15,EUR,IB0345~MAD~2026-11-10T09:00~Europe/Madrid~BCN~2026-11-10T10:25~Europe/Madrid\n";

    @Test
    @DisplayName("AC-1: a line becomes a FlightOffer with local times converted through their zones")
    void parsesValidLine() {
        String line = "GAM-20261106-000001-0,459.15,EUR,"
                + "VY0001~DUB~2026-11-06T03:05~Europe/Dublin~EZE~2026-11-06T10:40~America/Argentina/Buenos_Aires|"
                + "VY8227~EZE~2026-11-06T15:25~America/Argentina/Buenos_Aires~FRA~2026-11-07T00:55~Europe/Berlin\n";

        PartitionBuffer buffer = parse(parser, HEADER + line);

        assertThat(buffer.offers()).hasSize(1);
        FlightOffer offer = buffer.offers().getFirst();
        assertThat(offer.provider()).isEqualTo(new ProviderId("mock-gamma"));
        assertThat(offer.basePrice()).isEqualTo(Money.eur("459.15"));
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
    @DisplayName("AC-2: a flight across zones that 'lands before it takes off' in local time is a 4 h 20 min flight")
    void convertsAcrossZones() {
        String line =
                "GAM-2,300.00,EUR,TP0201~OPO~2026-11-06T12:55~Europe/Lisbon~BOS~2026-11-06T12:15~America/New_York\n";

        Segment segment = parse(parser, HEADER + line)
                .offers()
                .getFirst()
                .itinerary()
                .segments()
                .getFirst();

        assertThat(segment.departure()).isEqualTo(utc("2026-11-06T12:55:00Z"));
        assertThat(segment.arrival()).isEqualTo(utc("2026-11-06T17:15:00Z"));
        assertThat(Duration.between(segment.departure(), segment.arrival())).isEqualTo(Duration.ofMinutes(260));
    }

    @Test
    @DisplayName("AC-3: zero-padded flight numbers are canonical (IB0345 -> IB345)")
    void normalisesFlightNumber() {
        Segment segment = parse(parser, HEADER + VALID)
                .offers()
                .getFirst()
                .itinerary()
                .segments()
                .getFirst();

        assertThat(segment.flightNumber()).isEqualTo(FlightNumber.parse("IB345"));
        assertThat(segment.flightNumber()).hasToString("IB345");
    }

    @Test
    @DisplayName("AC-4: a header-only feed is a successful partition with zero offers")
    void emptyFeed() {
        assertThat(parse(parser, HEADER).recordsReceived()).isZero();
    }

    @Test
    @DisplayName("AC-1: CRLF line endings and blank lines are tolerated")
    void toleratesCrlfAndBlankLines() {
        String body = HEADER.replace("\n", "\r\n") + "\n" + VALID.replace("\n", "\r\n");

        PartitionBuffer buffer = parse(parser, body);

        assertThat(buffer.offers()).hasSize(1);
        assertThat(buffer.recordsReceived()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-5: records reach the buffer while the body is read, before the stream fails")
    void streamsRecords() {
        PartitionBuffer buffer = new PartitionBuffer();

        assertThatThrownBy(
                        () -> parser.parse(failingAfter(HEADER + VALID + VALID), ExchangeRates.unavailable(), buffer))
                .isInstanceOf(IOException.class);
        assertThat(buffer.offers()).hasSize(2);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "bad airport code    | ~MAD~                   | ~M4D~                    | BAD_AIRPORT_CODE",
                "negative price      | ,127.15,                | ,-12.50,                 | NON_POSITIVE_PRICE",
                "zero price          | ,127.15,                | ,0,                      | NON_POSITIVE_PRICE",
                "unparseable time    | 2026-11-10T09:00        | 10/11/2026 9h            | UNPARSEABLE_TIME",
                "non-numeric flight  | IB0345                  | IBABCD                   | BAD_FLIGHT_NUMBER",
                "too long flight     | IB0345                  | IB03456                  | BAD_FLIGHT_NUMBER",
                "non-numeric price   | ,127.15,                | ,abc,                    | INVALID_RECORD",
                "unknown zone        | ~Europe/Madrid~BCN      | ~Mars/Olympus~BCN        | INVALID_RECORD",
                "6 sub-fields        | ~BCN~2026-11-10T10:25~Europe/Madrid | ~BCN~2026-11-10T10:25 | INVALID_RECORD",
                "missing field       | GAM-1,127.15,EUR,       | GAM-1,EUR,               | INVALID_RECORD",
                "extra field         | ,127.15,EUR,            | ,127.15,EUR,EUR,         | INVALID_RECORD",
                "same origin/dest    | ~BCN~                   | ~MAD~                    | INVALID_RECORD",
            })
    @DisplayName("AC-6: a malformed line is counted under its reason and skipped; the next line is still parsed")
    void countsMalformedRecords(String name, String from, String to, MalformedReason reason) {
        String broken =
                VALID.replaceFirst(java.util.regex.Pattern.quote(from), java.util.regex.Matcher.quoteReplacement(to));

        PartitionBuffer buffer = parse(parser, HEADER + broken + VALID);

        assertThat(buffer.malformed()).as(name).containsOnlyKeys(reason);
        assertThat(buffer.malformed(reason)).as(name).isEqualTo(1);
        assertThat(buffer.offers()).as(name).hasSize(1);
        assertThat(buffer.recordsReceived()).as(name).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-6: one reason per record, the first failing field in document order")
    void firstFailingFieldWins() {
        String twoFaults = VALID.replace(",127.15,", ",-1,").replace("~MAD~", "~M4D~");

        assertThat(parse(parser, HEADER + twoFaults).malformed()).containsOnlyKeys(MalformedReason.NON_POSITIVE_PRICE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "last line without newline | REF,PRICE,CURRENCY,SEGMENTS\\n%s%s",
                "wrong header              | REF;PRICE;CURRENCY;SEGMENTS\\n%s",
                "missing header            | %s",
                "empty body                | ''",
            })
    @DisplayName("AC-7: a truncated or structurally broken feed fails as MALFORMED_DOCUMENT")
    void rejectsBrokenDocument(String name, String template) {
        String body = template.replace("\\n", "\n").formatted(VALID, VALID.strip());

        assertThatThrownBy(() -> parse(parser, body)).as(name).isInstanceOf(MalformedDocumentException.class);
    }

    @Test
    @DisplayName("AC-5/AC-7: a line longer than the limit fails the document instead of growing memory without bound")
    void rejectsHugeLine() {
        String huge = "GAM-1,1.00,EUR," + "x".repeat(70_000) + "\n";

        assertThatThrownBy(() -> parse(parser, HEADER + huge)).isInstanceOf(MalformedDocumentException.class);
    }
}
