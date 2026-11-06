package com.andresps.flights;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** Boots the full application on a random port and checks it reports itself healthy over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPostgres.class)
class ApplicationHealthIT {

    @Value("${local.server.port}")
    private int port;

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
