package com.andresps.gdsmock.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.support.Http;
import com.andresps.gdsmock.support.ParsedRecord;
import com.andresps.gdsmock.support.ProviderPayloads;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spec 008 AC-12/AC-13 through HTTP: Beta and Gamma configured with [EUR, USD, GBP] serve a mix of currencies per
 * record, deterministically; Alpha, left at the default [EUR], serves only EUR.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gds.fx.rates.USD=1.10",
            "gds.fx.rates.GBP=0.85",
            "gds.providers.mock-beta.currencies=EUR,USD,GBP",
            "gds.providers.mock-gamma.currencies=EUR,USD,GBP"
        })
@ActiveProfiles({"deterministic", "it"})
class ProviderCurrencyIT {

    private static final String DAY = "?date=2026-11-06";

    @Value("${local.server.port}")
    private int port;

    private Http http;

    @BeforeEach
    void client() {
        http = new Http(port);
    }

    private List<String> currencies(String provider, Function<byte[], List<ParsedRecord>> parser) {
        return parser.apply(http.get("/" + provider + "/flights" + DAY).getBody()).stream()
                .map(ParsedRecord::currency)
                .toList();
    }

    @Test
    @DisplayName("AC-12: Beta and Gamma mix EUR, USD and GBP per record; Alpha (default [EUR]) is all EUR")
    void feedsMixCurrencies() {
        assertThat(currencies("mock-alpha", ProviderPayloads::parseAlpha))
                .isNotEmpty()
                .containsOnly("EUR");
        assertThat(currencies("mock-beta", ProviderPayloads::parseBeta)).contains("EUR", "USD", "GBP");
        assertThat(currencies("mock-gamma", ProviderPayloads::parseGamma)).contains("EUR", "USD", "GBP");
    }

    @Test
    @DisplayName("AC-12: the same request always serves the same currencies and prices")
    void deterministic() {
        assertThat(http.get("/mock-beta/flights" + DAY).getBody())
                .isEqualTo(http.get("/mock-beta/flights" + DAY).getBody());
    }
}
