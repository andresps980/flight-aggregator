package com.andresps.gdsmock;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.support.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots with the default profile (chaos on). It only calls endpoints without chaos, so it stays fast: the defaults
 * are checked through the admin listing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "gds.world.volume=1000")
class DefaultProfileIT {

    @Value("${local.server.port}")
    private int port;

    @Test
    @DisplayName("AC-21: /actuator/health reports UP")
    void healthUp() {
        var health = new Http(port).getText("/actuator/health");

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("AC-15, AC-16: by default every provider has 100-5000 ms latency and a 10 % error rate")
    void defaultChaos() {
        JsonNode providers = JsonMapper.builder()
                .build()
                .readTree(new Http(port).get("/admin/providers").getBody());

        assertThat(providers.size()).isEqualTo(3);
        for (JsonNode provider : providers) {
            assertThat(provider.get("latencyMinMs").longValue()).isEqualTo(100);
            assertThat(provider.get("latencyMaxMs").longValue()).isEqualTo(5_000);
            assertThat(provider.get("errorRate").doubleValue()).isEqualTo(0.10);
            assertThat(provider.get("outage").booleanValue()).isFalse();
        }
    }
}
