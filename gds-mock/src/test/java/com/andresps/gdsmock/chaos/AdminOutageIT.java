package com.andresps.gdsmock.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.support.Http;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"deterministic", "it"})
class AdminOutageIT {

    @Value("${local.server.port}")
    private int port;

    private Http http;

    @BeforeEach
    void client() {
        http = new Http(port);
    }

    @AfterEach
    void recoverEveryProvider() {
        http.delete("/admin/providers/mock-alpha/outage");
        http.delete("/admin/providers/mock-beta/outage");
        http.delete("/admin/providers/mock-gamma/outage");
    }

    @Test
    @DisplayName("AC-17: a forced outage makes only that provider answer 503 until it is recovered")
    void outageAndRecovery() {
        assertThat(http.post("/admin/providers/mock-beta/outage").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        var down = http.getText("/mock-beta/flights");
        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(down.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(down.getBody()).contains("mock-beta");
        assertThat(http.get("/mock-alpha/flights?date=2026-11-06").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.get("/mock-gamma/flights?date=2026-11-06").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(http.delete("/admin/providers/mock-beta/outage").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(http.get("/mock-beta/flights?date=2026-11-06").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC-17, AC-19: the admin listing shows each provider's chaos settings and outage state")
    void listing() {
        http.post("/admin/providers/mock-gamma/outage");

        JsonNode providers = JsonMapper.builder()
                .build()
                .readTree(http.get("/admin/providers").getBody());

        assertThat(providers.size()).isEqualTo(3);
        assertThat(providers.get(0).get("id").asString()).isEqualTo("mock-alpha");
        assertThat(providers.get(2).get("id").asString()).isEqualTo("mock-gamma");
        for (JsonNode provider : providers) {
            assertThat(provider.get("latencyMinMs").longValue()).isZero();
            assertThat(provider.get("latencyMaxMs").longValue()).isZero();
            assertThat(provider.get("errorRate").doubleValue()).isZero();
        }
        assertThat(providers.get(0).get("outage").booleanValue()).isFalse();
        assertThat(providers.get(2).get("outage").booleanValue()).isTrue();
    }

    @Test
    @DisplayName("AC-18 (error): an unknown provider on the admin API is a 404 Problem Detail")
    void unknownProvider() {
        var post = http.post("/admin/providers/mock-delta/outage");
        var delete = http.delete("/admin/providers/mock-delta/outage");

        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC-21: health stays UP while every provider is in outage")
    void healthDuringOutage() {
        http.post("/admin/providers/mock-alpha/outage");
        http.post("/admin/providers/mock-beta/outage");
        http.post("/admin/providers/mock-gamma/outage");

        var health = http.getText("/actuator/health");

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }
}
