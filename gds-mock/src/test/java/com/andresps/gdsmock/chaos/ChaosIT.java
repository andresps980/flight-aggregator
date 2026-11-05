package com.andresps.gdsmock.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.support.Http;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/** Per-provider chaos overrides on top of the deterministic profile: Beta always fails, Gamma is slow. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gds.chaos.providers.mock-beta.error-rate=1",
            "gds.chaos.providers.mock-gamma.latency-min=300ms",
            "gds.chaos.providers.mock-gamma.latency-max=300ms"
        })
@ActiveProfiles({"deterministic", "it"})
class ChaosIT {

    @Value("${local.server.port}")
    private int port;

    private Http http;

    @BeforeEach
    void client() {
        http = new Http(port);
    }

    @Test
    @DisplayName("AC-16: a provider with error-rate 1 always answers 500 Problem Details; the others are unaffected")
    void errorRateOne() {
        var beta = http.getText("/mock-beta/flights");

        assertThat(beta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(beta.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(beta.getBody()).contains("mock-beta");
        assertThat(http.get("/mock-alpha/flights").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC-15: a provider with a 300 ms latency answers no sooner than 300 ms")
    void latency() {
        long start = System.nanoTime();
        var gamma = http.get("/mock-gamma/flights?date=2026-11-06");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(gamma.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("AC-14: invalid requests get 400/404 without chaos, even on a provider that always fails")
    void invalidRequestsSkipChaos() {
        assertThat(http.getText("/mock-beta/flights?date=2026-11-05").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getText("/mock-delta/flights").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC-16, AC-21: chaos applies to provider feeds only, never to admin or health endpoints")
    void chaosOnlyOnFeeds() {
        assertThat(http.getText("/admin/providers").getBody())
                .contains("\"id\":\"mock-beta\"")
                .contains("\"errorRate\":1.0");
        assertThat(http.getText("/admin/world/stats").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getText("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
