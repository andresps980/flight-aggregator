package com.andresps.gdsmock.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.inventory.domain.generator.WorldGenerator;
import com.andresps.gdsmock.support.Http;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 008 AC-15: the Frankfurter-compatible FX double, serving the rates the mock prices with. Every provider fails with error-rate 1, to show the FX endpoint is not subject to chaos.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gds.fx.rates.USD=1.25",
            "gds.fx.rates.GBP=0.80",
            "gds.providers.mock-beta.currencies=EUR,USD,GBP",
            "gds.chaos.default.error-rate=1"
        })
@ActiveProfiles({"deterministic", "it"})
class FxRatesIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private WorldGenerator generator;

    private Http http;

    @BeforeEach
    void client() {
        http = new Http(port);
    }

    @Test
    @DisplayName("AC-15: GET /fx/latest?base=EUR answers the Frankfurter shape with the configured rates")
    void latest() {
        var response = http.getText("/fx/latest?base=EUR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.path("amount").asDouble()).isEqualTo(1.0);
        assertThat(body.path("base").asString()).isEqualTo("EUR");
        assertThat(body.path("date").asString())
                .isEqualTo(generator.horizon().referenceDate().toString());
        assertThat(body.path("rates").path("USD").decimalValue()).isEqualByComparingTo("1.25");
        assertThat(body.path("rates").path("GBP").decimalValue()).isEqualByComparingTo("0.80");
        assertThat(body.path("rates").has("EUR")).isFalse();
    }

    @Test
    @DisplayName("AC-15: base defaults to EUR; any other base is a 400 Problem Details")
    void otherBase() {
        assertThat(http.getText("/fx/latest").getStatusCode()).isEqualTo(HttpStatus.OK);

        var usd = http.getText("/fx/latest?base=USD");

        assertThat(usd.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(usd.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(usd.getBody()).contains("base");
    }

    @Test
    @DisplayName("AC-15: the FX endpoint is never delayed or failed by chaos, even with error-rate 1")
    void noChaos() {
        assertThat(http.getText("/mock-alpha/flights").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        for (int i = 0; i < 5; i++) {
            assertThat(http.getText("/fx/latest?base=EUR").getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
