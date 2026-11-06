package com.andresps.flights.search;

import static com.andresps.flights.search.SearchRows.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.support.TestPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Spec 005 AC-21 with Redis unreachable from the start (connection refused): the application starts, is ready, and
 * search and catalog answer from PostgreSQL. Its own context, pointed at a port where nothing listens.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.data.redis.host=localhost", "spring.data.redis.port=1"})
@Import({TestPostgres.class, SearchSpringTest.SearchTime.class})
class SearchRedisDownIT {

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meters;

    private RestClient http;

    @BeforeEach
    void seed() {
        SearchSpringTest.CLOCK.set(SearchSpringTest.NOW);
        TestPostgres.truncateAll(dataSource);
        SearchRows rows = new SearchRows(dataSource);
        rows.insert(row("MAD", "JFK", "IB", "2026-11-07T10:00:00Z", "400.00").withKey("k1"));
        rows.completeDay(LocalDate.parse("2026-11-07"));
        rows.publish();
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> {})
                .build();
    }

    private ResponseEntity<JsonNode> get(String path) {
        return http.get().uri(path).retrieve().toEntity(JsonNode.class);
    }

    private double count(String result) {
        var counter = meters.find("search.cache").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("AC-21: Redis down: search and catalog answer 200 from PostgreSQL, failures are counted")
    void answersWithoutRedis() {
        for (int i = 0; i < 3; i++) {
            ResponseEntity<JsonNode> search = get("/api/v1/itineraries?origin=MAD");
            assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(search.getBody().get("items").get(0).get("itineraryKey").asString())
                    .isEqualTo("k1");
        }
        ResponseEntity<JsonNode> catalog = get("/api/v1/catalog");

        assertThat(catalog.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalog.getBody().get("origins").toString()).isEqualTo("[\"MAD\"]");
        double failures = count("error") + count("bypassed");
        assertThat(failures).isPositive();
    }

    @Test
    @DisplayName("AC-21: Redis down: the application is ready; overall health reports Redis down")
    void readyWithoutRedis() {
        assertThat(get("/actuator/health/readiness").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
