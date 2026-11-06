package com.andresps.flights;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.ingestion.RecordedFeedsSpringTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Boots the full application on a random port (with PostgreSQL) and checks it reports itself healthy over HTTP. Shares
 * the recorded-feeds context.
 */
class ApplicationHealthIT extends RecordedFeedsSpringTest {

    @Test
    void reportsHealthUp() {
        var response = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/actuator/health")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
