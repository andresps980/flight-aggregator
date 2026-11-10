package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.inventory.domain.model.Airport;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormat;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 008 AC-12: each format writes every record's own currency next to its price, as the record says. */
class ForeignCurrencyFormatTest {

    private static final Airport MAD = new Airport("MAD", ZoneId.of("Europe/Madrid"));
    private static final Airport BCN = new Airport("BCN", ZoneId.of("Europe/Madrid"));

    /** IB345 MAD→BCN priced {@code price} in {@code currency}. */
    private static ProviderRecord record(String provider, String index, String price, String currency) {
        return new ProviderRecord(
                new ProviderId(provider),
                "20261110-0000" + index + "-0",
                0,
                List.of(new ScheduledSegment(
                        "IB",
                        345,
                        MAD,
                        BCN,
                        Instant.parse("2026-11-10T08:00:00Z"),
                        Instant.parse("2026-11-10T09:25:00Z"))),
                new BigDecimal(price),
                Currency.getInstance(currency),
                Optional.empty());
    }

    private static List<ProviderRecord> mixed(String provider) {
        return List.of(
                record(provider, "17", "129.90", "EUR"),
                record(provider, "18", "142.89", "USD"),
                record(provider, "19", "110.42", "GBP"));
    }

    private static String write(ProviderFormat format, List<ProviderRecord> records) {
        var out = new ByteArrayOutputStream();
        try {
            format.write(records.iterator(), out);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-12: Mock-Alpha writes each record's \"cur\" next to its totFare")
    void alpha() {
        assertThat(write(new AlphaJsonFormat(), mixed("mock-alpha")))
                .contains("\"totFare\":129.90,\"cur\":\"EUR\"")
                .contains("\"totFare\":142.89,\"cur\":\"USD\"")
                .contains("\"totFare\":110.42,\"cur\":\"GBP\"");
    }

    @Test
    @DisplayName("AC-12: Mock-Beta writes each itinerary's currency attribute")
    void beta() {
        assertThat(write(new BetaXmlFormat(), mixed("mock-beta")))
                .contains("<totalAmount currency=\"EUR\">129.90</totalAmount>")
                .contains("<totalAmount currency=\"USD\">142.89</totalAmount>")
                .contains("<totalAmount currency=\"GBP\">110.42</totalAmount>");
    }

    @Test
    @DisplayName("AC-12: Mock-Gamma writes header REF,PRICE,CURRENCY,SEGMENTS and a currency per line")
    void gamma() {
        String[] lines = write(new GammaCsvFormat(), mixed("mock-gamma")).split("\n");

        assertThat(lines[0]).isEqualTo("REF,PRICE,CURRENCY,SEGMENTS");
        assertThat(lines[1]).startsWith("GAM-20261110-000017-0,129.90,EUR,IB0345~MAD~");
        assertThat(lines[2]).startsWith("GAM-20261110-000018-0,142.89,USD,IB0345~MAD~");
        assertThat(lines[3]).startsWith("GAM-20261110-000019-0,110.42,GBP,IB0345~MAD~");
    }
}
