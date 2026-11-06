package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.RecordedGdsMock.DAYS;
import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.andresps.flights.ingestion.RecordedGdsMock.feed;
import static com.andresps.flights.ingestion.RecordedGdsMock.ok;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.andresps.flights.support.TestPostgres;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Spec 004 AC-21..AC-23 over real HTTP: {@code POST /api/v1/ingestions}, {@code GET /api/v1/ingestions/{id}} and
 * {@code /latest}. Provider feeds answer after {@value #LATENCY_MS} ms, so a run stays in progress long enough to be
 * observed.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "aggregator.ingestion.horizon-days=1",
            "aggregator.resilience.default.read-timeout=3s",
            "aggregator.resilience.default.attempt-timeout=10s"
        })
@Import(TestPostgres.class)
class IngestionApiIT {

    private static final int LATENCY_MS = 800;
    private static final WireMockServer SERVER = startServer();

    private static WireMockServer startServer() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        PROVIDERS.forEach(p -> registry.add("aggregator.providers." + p + ".base-url", SERVER::baseUrl));
    }

    /** A ticking clock that starts at the recorded fixtures' reference instant, so feeds match the requested day. */
    @TestConfiguration
    static class RecordedTime {
        @Bean
        @Primary
        Clock clock() {
            return Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), RecordedGdsMock.CLOCK.instant()));
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CircuitBreakerRegistry breakers;

    private RestClient http;

    @BeforeEach
    void reset() {
        TestPostgres.truncateAll(dataSource);
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        SERVER.resetAll();
        PROVIDERS.forEach(provider -> SERVER.stubFor(request(provider, DAYS.getFirst())
                .willReturn(ok(provider, feed(provider, DAYS.getFirst())).withFixedDelay(LATENCY_MS))));
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> {})
                .build();
    }

    @AfterEach
    void waitForRunsToFinish() {
        await().atMost(Duration.ofSeconds(30)).until(() -> !"RUNNING".equals(latestStatus()));
    }

    private String latestStatus() {
        ResponseEntity<JsonNode> latest = get("/api/v1/ingestions/latest");
        return latest.getStatusCode().is2xxSuccessful()
                ? latest.getBody().get("status").asString()
                : "none";
    }

    private ResponseEntity<JsonNode> post() {
        return http.post().uri("/api/v1/ingestions").retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return http.get().uri(path).retrieve().toEntity(JsonNode.class);
    }

    @Test
    @DisplayName("AC-21: POST answers 202 with Location and the RUNNING run; the run then completes asynchronously")
    void postStartsARun() {
        ResponseEntity<JsonNode> response = post();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = response.getBody();
        String id = body.get("id").asString();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(response.getHeaders().getLocation())
                .hasToString("http://localhost:" + port + "/api/v1/ingestions/" + id);
        assertThat(body.get("status").asString()).isEqualTo("RUNNING");
        assertThat(body.get("trigger").asString()).isEqualTo("MANUAL");
        assertThat(body.get("startedAt").asString()).isNotBlank();
        assertThat(body.get("report").isNull()).isTrue();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> "COMPLETED"
                        .equals(get("/api/v1/ingestions/" + id)
                                .getBody()
                                .get("status")
                                .asString()));
        JsonNode finished = get("/api/v1/ingestions/" + id).getBody();
        assertThat(finished.get("finishedAt").asString()).isNotBlank();
        assertThat(finished.get("durationMs").asLong()).isPositive();
        assertThat(finished.get("report").get("days")).hasSize(1);
        assertThat(finished.get("report").get("days").get(0).get("outcome").asString())
                .isEqualTo("REPLACED");
        assertThat(finished.get("report").get("providers")).hasSize(3);
    }

    @Test
    @DisplayName("AC-21: a POST while a run is in progress answers 409 ProblemDetail with the running id")
    void conflictWhileRunning() {
        String running = post().getBody().get("id").asString();

        ResponseEntity<JsonNode> second = post();

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = second.getBody();
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asString()).isEqualTo("Ingestion already running");
        assertThat(problem.get("runningIngestionId").asString()).isEqualTo(running);
        assertThat(problem.get("detail").asString()).contains(running);
    }

    @Test
    @DisplayName("AC-22: GET by id while running has no report; latest is the most recently started run")
    void getRunningAndLatest() {
        String id = post().getBody().get("id").asString();

        JsonNode running = get("/api/v1/ingestions/" + id).getBody();
        JsonNode latest = get("/api/v1/ingestions/latest").getBody();

        assertThat(running.get("status").asString()).isEqualTo("RUNNING");
        assertThat(running.get("report").isNull()).isTrue();
        assertThat(running.get("finishedAt").isNull()).isTrue();
        assertThat(latest.get("id").asString()).isEqualTo(id);
    }

    @Test
    @DisplayName("AC-23 (error): unknown id and latest without runs answer 404; a malformed id answers 400")
    void errors() {
        ResponseEntity<JsonNode> latest = get("/api/v1/ingestions/latest");
        ResponseEntity<JsonNode> unknown = get("/api/v1/ingestions/" + UUID.randomUUID());
        ResponseEntity<JsonNode> malformed = get("/api/v1/ingestions/not-a-uuid");

        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(latest.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody().get("status").asInt()).isEqualTo(404);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
