package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ParserTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.deduplication.OfferAggregator;
import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.ExchangeRatesUnavailableException;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.pricing.PercentageMarkupPolicy;
import com.andresps.flights.aggregation.domain.validation.ItineraryValidator;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.alpha.AlphaJsonParser;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.beta.BetaXmlParser;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.gamma.GammaCsvParser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec 008: every record declares its own currency (Alpha {@code cur}, Beta {@code currency}, Gamma's
 * {@code CURRENCY} column) and the parsers convert each price to EUR with the run's rates before building the offer.
 * The itinerary is the parser tests' one: IB345 MAD → BCN, 2026-11-10 08:00Z – 09:25Z.
 */
class ProviderCurrencyTest {

    /** 1.25 USD and 0.80 GBP per EUR. */
    static final ExchangeRates RATES = ExchangeRates.of(
            LocalDate.parse("2026-11-09"),
            Map.of(
                    Currency.getInstance("USD"), new BigDecimal("1.25"),
                    Currency.getInstance("GBP"), new BigDecimal("0.80")));

    private final AlphaJsonParser alpha = new AlphaJsonParser();
    private final BetaXmlParser beta = new BetaXmlParser();
    private final GammaCsvParser gamma = new GammaCsvParser();

    // ----- Alpha -----

    static String alphaRecord(String amount, String currencyField) {
        return """
                {"offerRef":"ALP-1","cxr":"IB","totFare":%s%s,
                 "legs":[{"fltNum":"IB345","org":"MAD","dst":"BCN","depTs":1794297600000,"arrTs":1794302700000}]}""".formatted(amount, currencyField);
    }

    static String alphaPriced(String amount, String currency) {
        return alphaRecord(amount, ",\"cur\":\"" + currency + "\"");
    }

    static String alphaFeed(String... records) {
        return "[" + String.join(",", records) + "]";
    }

    // ----- Beta -----

    static String betaItinerary(String amount, String currencyAttribute) {
        return "<itinerary id=\"BET-1\"><pricing><totalAmount%s>%s</totalAmount></pricing>"
                        .formatted(currencyAttribute, amount)
                + "<originDestinationOptions><option><segments><segment><carrier>IB</carrier>"
                + "<flightNumber>345</flightNumber>"
                + "<departure airport=\"MAD\" dateTime=\"2026-11-10T09:00:00+01:00\"/>"
                + "<arrival airport=\"BCN\" dateTime=\"2026-11-10T10:25:00+01:00\"/></segment>"
                + "</segments></option></originDestinationOptions></itinerary>";
    }

    static String betaPriced(String amount, String currency) {
        return betaItinerary(amount, " currency=\"" + currency + "\"");
    }

    static String betaFeed(String... itineraries) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><response provider=\"mock-beta\"><itineraries>"
                + String.join("", itineraries) + "</itineraries></response>";
    }

    // ----- Gamma -----

    static final String GAMMA_HEADER = "REF,PRICE,CURRENCY,SEGMENTS\n";

    static String gammaLine(String amount, String currency) {
        return "GAM-1," + amount + "," + currency
                + ",IB0345~MAD~2026-11-10T09:00~Europe/Madrid~BCN~2026-11-10T10:25~Europe/Madrid\n";
    }

    static String gammaFeed(String... lines) {
        return GAMMA_HEADER + String.join("", lines);
    }

    private static Money price(PartitionBuffer buffer) {
        assertThat(buffer.malformed()).isEmpty();
        return buffer.offers().getFirst().basePrice();
    }

    @Nested
    class Conversion {

        @Test
        @DisplayName("AC-1: an Alpha record priced 100.00 USD has a base price of 80.00 EUR")
        void alphaUsd() {
            assertThat(price(parse(alpha, alphaFeed(alphaPriced("100.00", "USD")), RATES)))
                    .isEqualTo(Money.eur("80.00"));
        }

        @Test
        @DisplayName("AC-1: the Alpha currency may come before the amount in the record")
        void alphaCurrencyFirst() {
            String record = """
                    [{"cur":"USD","offerRef":"ALP-1","cxr":"IB","totFare":100.00,
                      "legs":[{"fltNum":"IB345","org":"MAD","dst":"BCN","depTs":1794297600000,"arrTs":1794302700000}]}]""";

            assertThat(price(parse(alpha, record, RATES))).isEqualTo(Money.eur("80.00"));
        }

        @Test
        @DisplayName("AC-1: a Beta itinerary priced 100.00 USD has a base price of 80.00 EUR")
        void betaUsd() {
            assertThat(price(parse(beta, betaFeed(betaPriced("100.00", "USD")), RATES)))
                    .isEqualTo(Money.eur("80.00"));
        }

        @Test
        @DisplayName("AC-1: a Gamma line priced 100.00 GBP has a base price of 125.00 EUR")
        void gammaGbp() {
            assertThat(price(parse(gamma, gammaFeed(gammaLine("100.00", "GBP")), RATES)))
                    .isEqualTo(Money.eur("125.00"));
        }

        @Test
        @DisplayName("AC-2: one feed mixing EUR, USD and GBP converts each record with its own rate")
        void mixedCurrencies() {
            String[] amounts = {"100.00", "100.00", "100.00"};
            String[] currencies = {"EUR", "USD", "GBP"};
            Money[] expected = {Money.eur("100.00"), Money.eur("80.00"), Money.eur("125.00")};

            PartitionBuffer alphaBuffer = parse(
                    alpha,
                    alphaFeed(
                            alphaPriced(amounts[0], currencies[0]),
                            alphaPriced(amounts[1], currencies[1]),
                            alphaPriced(amounts[2], currencies[2])),
                    RATES);
            PartitionBuffer betaBuffer = parse(
                    beta,
                    betaFeed(
                            betaPriced(amounts[0], currencies[0]),
                            betaPriced(amounts[1], currencies[1]),
                            betaPriced(amounts[2], currencies[2])),
                    RATES);
            PartitionBuffer gammaBuffer = parse(
                    gamma,
                    gammaFeed(
                            gammaLine(amounts[0], currencies[0]),
                            gammaLine(amounts[1], currencies[1]),
                            gammaLine(amounts[2], currencies[2])),
                    RATES);

            for (PartitionBuffer buffer : new PartitionBuffer[] {alphaBuffer, betaBuffer, gammaBuffer}) {
                assertThat(buffer.offers()).extracting(FlightOffer::basePrice).containsExactly(expected);
                assertThat(buffer.malformed()).isEmpty();
            }
        }

        @Test
        @DisplayName("AC-3: EUR records of every format are used as is when the rates are unavailable")
        void eurWithoutRates() {
            ExchangeRates none = ExchangeRates.unavailable();

            assertThat(price(parse(alpha, alphaFeed(alphaPriced("129.90", "EUR")), none)))
                    .isEqualTo(Money.eur("129.90"));
            assertThat(price(parse(beta, betaFeed(betaPriced("131.40", "EUR")), none)))
                    .isEqualTo(Money.eur("131.40"));
            assertThat(price(parse(gamma, gammaFeed(gammaLine("127.15", "EUR")), none)))
                    .isEqualTo(Money.eur("127.15"));
        }

        @Test
        @DisplayName("AC-4: the converted amount is rounded once, HALF_EVEN (0.05 at 2 -> 0.02; 0.15 -> 0.08)")
        void roundsOnce() {
            ExchangeRates two = ExchangeRates.of(
                    LocalDate.parse("2026-11-09"), Map.of(Currency.getInstance("USD"), new BigDecimal("2")));

            assertThat(price(parse(alpha, alphaFeed(alphaPriced("0.05", "USD")), two)))
                    .isEqualTo(Money.eur("0.02"));
            assertThat(price(parse(beta, betaFeed(betaPriced("0.15", "USD")), two)))
                    .isEqualTo(Money.eur("0.08"));
            assertThat(price(parse(gamma, gammaFeed(gammaLine("100", "USD")), two)))
                    .isEqualTo(Money.eur("50.00"));
        }
    }

    @Nested
    class Aggregation {

        @Test
        @DisplayName("AC-5: Alpha at 100.00 USD (80.00 EUR) beats Beta at 85.00 EUR; final price 84.00 EUR")
        void cheapestAfterConversion() {
            OfferAggregator aggregator = new OfferAggregator(
                    ItineraryValidator.withDefaultRules(
                            Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC)),
                    PercentageMarkupPolicy.defaultPolicy());

            parse(alpha, alphaFeed(alphaPriced("100.00", "USD")), RATES)
                    .offers()
                    .forEach(aggregator::accept);
            parse(beta, betaFeed(betaPriced("85.00", "EUR")), RATES).offers().forEach(aggregator::accept);

            assertThat(aggregator.result().itineraries()).hasSize(1);
            AggregatedItinerary selected = aggregator.result().itineraries().getFirst();
            assertThat(selected.selectedOffer().provider()).isEqualTo(AlphaJsonParser.PROVIDER);
            assertThat(selected.basePrice()).isEqualTo(Money.eur("80.00"));
            assertThat(selected.finalPrice()).isEqualTo(Money.eur("84.00"));
        }
    }

    @Nested
    class Malformed {

        @Test
        @DisplayName("AC-6/AC-8: with a bad currency and a non-positive price, the reason is the first field in "
                + "document order")
        void firstFailingFieldInDocumentOrder() {
            String alphaCurrencyFirst = """
                    [{"cur":"XYZ","totFare":-5.00,
                      "legs":[{"fltNum":"IB345","org":"MAD","dst":"BCN","depTs":1794297600000,"arrTs":1794302700000}]}]""";

            // Alpha: totFare before cur in the usual layout, cur first here; Beta: the currency attribute comes first
            assertThat(parse(alpha, alphaFeed(alphaPriced("-5.00", "XYZ")), RATES)
                            .malformed())
                    .containsOnlyKeys(MalformedReason.NON_POSITIVE_PRICE);
            assertThat(parse(alpha, alphaCurrencyFirst, RATES).malformed())
                    .containsOnlyKeys(MalformedReason.INVALID_RECORD);
            assertThat(parse(beta, betaFeed(betaPriced("-5.00", "XYZ")), RATES).malformed())
                    .containsOnlyKeys(MalformedReason.INVALID_RECORD);
            assertThat(parse(gamma, gammaFeed(gammaLine("-5.00", "XYZ")), RATES).malformed())
                    .containsOnlyKeys(MalformedReason.NON_POSITIVE_PRICE);
        }

        private static void assertOneInvalidThenOneOffer(PartitionBuffer buffer, String currency) {
            assertThat(buffer.malformed())
                    .as("currency '%s'", currency)
                    .containsExactly(Map.entry(MalformedReason.INVALID_RECORD, 1L));
            assertThat(buffer.offers()).as("currency '%s'", currency).hasSize(1);
        }

        @ParameterizedTest(name = "cur = ''{0}''")
        @ValueSource(strings = {"", " ", "usd", "XYZ", "EURO", "XAF"})
        @DisplayName("AC-6: an Alpha record with a blank, non-ISO or unrated currency is INVALID_RECORD; the "
                + "next record is parsed")
        void alphaBadCurrency(String currency) {
            String feed = alphaFeed(alphaPriced("10.00", currency), alphaPriced("10.00", "USD"));

            assertOneInvalidThenOneOffer(parse(alpha, feed, RATES), currency);
        }

        @ParameterizedTest(name = "currency = ''{0}''")
        @ValueSource(strings = {"", " ", "usd", "XYZ", "EURO", "XAF"})
        @DisplayName("AC-6: a Beta itinerary with a blank, non-ISO or unrated currency is INVALID_RECORD")
        void betaBadCurrency(String currency) {
            String feed = betaFeed(betaPriced("10.00", currency), betaPriced("10.00", "USD"));

            assertOneInvalidThenOneOffer(parse(beta, feed, RATES), currency);
        }

        @ParameterizedTest(name = "CURRENCY = ''{0}''")
        @ValueSource(strings = {"", " ", "usd", "XYZ", "EURO", "XAF"})
        @DisplayName("AC-6: a Gamma line with a blank, non-ISO or unrated currency is INVALID_RECORD")
        void gammaBadCurrency(String currency) {
            String feed = gammaFeed(gammaLine("10.00", currency), gammaLine("10.00", "USD"));

            assertOneInvalidThenOneOffer(parse(gamma, feed, RATES), currency);
        }

        @Test
        @DisplayName("AC-6: Alpha and Beta records without a currency are INVALID_RECORD")
        void missingCurrency() {
            assertThat(parse(alpha, alphaFeed(alphaRecord("10.00", "")), RATES).malformed())
                    .containsExactly(Map.entry(MalformedReason.INVALID_RECORD, 1L));
            assertThat(parse(beta, betaFeed(betaItinerary("10.00", "")), RATES).malformed())
                    .containsExactly(Map.entry(MalformedReason.INVALID_RECORD, 1L));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "REF,PRICE_EUR,SEGMENTS",
                    "REF,PRICE,SEGMENTS",
                    "REF,CURRENCY,PRICE,SEGMENTS",
                    "REF,PRICE,CURRENCY,SEGMENTS,EXTRA",
                    "ref,price,currency,segments"
                })
        @DisplayName("AC-7: a Gamma header other than REF,PRICE,CURRENCY,SEGMENTS (the old PRICE_EUR one too) "
                + "fails the document")
        void gammaBadHeader(String header) {
            String body = header + "\n" + gammaLine("10.00", "EUR");

            assertThatThrownBy(() -> parse(gamma, body, RATES)).isInstanceOf(MalformedDocumentException.class);
        }

        @Test
        @DisplayName("AC-7: a Gamma line without 4 fields (the old 3-field layout) is INVALID_RECORD")
        void gammaOldLine() {
            String oldLine =
                    "GAM-1,10.00,IB0345~MAD~2026-11-10T09:00~Europe/Madrid~BCN~2026-11-10T10:25~Europe/Madrid\n";

            PartitionBuffer buffer = parse(gamma, gammaFeed(oldLine, gammaLine("10.00", "EUR")), RATES);

            assertThat(buffer.malformed()).containsExactly(Map.entry(MalformedReason.INVALID_RECORD, 1L));
            assertThat(buffer.offers()).hasSize(1);
        }

        @Test
        @DisplayName("AC-8: a non-positive price is NON_POSITIVE_PRICE whatever the currency")
        void nonPositiveWhateverCurrency() {
            assertThat(parse(alpha, alphaFeed(alphaPriced("0.00", "USD")), RATES)
                            .malformed())
                    .containsOnlyKeys(MalformedReason.NON_POSITIVE_PRICE);
            assertThat(parse(beta, betaFeed(betaPriced("-5.00", "XAF")), RATES).malformed())
                    .containsOnlyKeys(MalformedReason.NON_POSITIVE_PRICE);
            assertThat(parse(gamma, gammaFeed(gammaLine("0", "GBP")), RATES).malformed())
                    .containsOnlyKeys(MalformedReason.NON_POSITIVE_PRICE);
        }

        @Test
        @DisplayName("AC-8: a converted amount beyond the Money bounds is INVALID_RECORD")
        void convertedOutOfBounds() {
            // 999,999,999 GBP is within bounds; at 0.80 it is 1,249,999,998.75 EUR, which is not
            assertThat(parse(gamma, gammaFeed(gammaLine("999999999", "GBP")), RATES)
                            .malformed())
                    .containsExactly(Map.entry(MalformedReason.INVALID_RECORD, 1L));
        }

        @Test
        @DisplayName("AC-8: a provider amount beyond the Money bounds is INVALID_RECORD before converting")
        void providerAmountOutOfBounds() {
            assertThat(parse(alpha, alphaFeed(alphaPriced("1E999999999", "USD")), RATES)
                            .malformed())
                    .containsExactly(Map.entry(MalformedReason.INVALID_RECORD, 1L));
        }
    }

    @Nested
    class RatesUnavailable {

        @Test
        @DisplayName("AC-10: without rates, an Alpha non-EUR price fails the partition, not the record")
        void alpha() {
            String feed = alphaFeed(alphaPriced("10.00", "EUR"), alphaPriced("10.00", "USD"));

            assertThatThrownBy(() -> parse(alpha, feed, ExchangeRates.unavailable()))
                    .isInstanceOf(ExchangeRatesUnavailableException.class);
        }

        @Test
        @DisplayName("AC-10: without rates, a Beta non-EUR price fails the partition")
        void beta() {
            assertThatThrownBy(() -> parse(beta, betaFeed(betaPriced("10.00", "GBP")), ExchangeRates.unavailable()))
                    .isInstanceOf(ExchangeRatesUnavailableException.class);
        }

        @Test
        @DisplayName("AC-10: without rates, a Gamma non-EUR line fails the partition")
        void gamma() {
            String feed = gammaFeed(gammaLine("10.00", "EUR"), gammaLine("10.00", "USD"));

            assertThatThrownBy(() -> parse(gamma, feed, ExchangeRates.unavailable()))
                    .isInstanceOf(ExchangeRatesUnavailableException.class);
        }

        @Test
        @DisplayName("AC-10: without rates, an invalid currency code is still only an INVALID_RECORD")
        void invalidCurrencyStaysRecordLevel() {
            assertThat(parse(alpha, alphaFeed(alphaPriced("10.00", "XYZ")), ExchangeRates.unavailable())
                            .malformed())
                    .containsExactly(Map.entry(MalformedReason.INVALID_RECORD, 1L));
        }
    }
}
