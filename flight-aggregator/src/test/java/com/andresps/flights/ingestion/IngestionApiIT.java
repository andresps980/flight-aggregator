package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.RecordedGdsMock.DAYS;
import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.andresps.flights.ingestion.RecordedGdsMock.feed;
import static com.andresps.flights.ingestion.RecordedGdsMock.ok;
import static com.andresps.flights.ingestion.RecordedGdsMock.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.andresps.flights.support.StoredData;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Spec 004 AC-21..AC-23 over real HTTP: {@code POST /api/v1/ingestions}, {@code GET /api/v1/ingestions/{id}} and
 * {@code /latest}. Provider feeds answer after {@value #LATENCY_MS} ms (below the 1 s read timeout), so a run stays
 * in progress long enough to be observed.
 */
class IngestionApiIT extends RecordedFeedsSpringTest {

    private static final int LATENCY_MS = 400;

    private RestClient http;

    @BeforeEach
    void slowFeeds() {
        SERVER.resetAll();
        PROVIDERS.forEach(provider -> DAYS.forEach(day -> SERVER.stubFor(request(provider, day)
                .willReturn(ok(provider, feed(provider, day)).withFixedDelay(LATENCY_MS)))));
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> {})
                .build();
    }

    @AfterEach
    void waitForRunsToFinish() {
        // The final state is stored just before the lock is released: wait for both, or the next POST could get a 409.
        StoredData stored = new StoredData(dataSource);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> !"RUNNING".equals(latestStatus()) && stored.advisoryLocks() == 0);
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
    @DisplayName(
            "spec 004 AC-21: POST answers 202 with Location and the RUNNING run; the run then completes asynchronously")
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
        assertThat(finished.get("report").get("days")).hasSize(DAYS.size());
        finished.get("report")
                .get("days")
                .forEach(day -> assertThat(day.get("outcome").asString()).isEqualTo("REPLACED"));
        assertThat(finished.get("report").get("providers")).hasSize(3);
    }

    @Test
    @DisplayName("spec 004 AC-21: a POST while a run is in progress answers 409 ProblemDetail with the running id")
    void conflictWhileRunning() {
        String running = post().getBody().get("id").asString();

        ResponseEntity<JsonNode> second = post();

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = second.getBody();
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asString()).isEqualTo("Ingestion already running");
        assertThat(problem.get("runningIngestionId").asString()).isEqualTo(running);
        assertThat(problem.get("instance").asString()).isEqualTo("/api/v1/ingestions");
        assertThat(problem.get("detail").asString()).contains(running);
    }

    @Test
    @DisplayName("spec 004 AC-22: GET by id while running has no report; latest is the most recently started run")
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
    @DisplayName("spec 004 AC-23 (error): unknown id and latest without runs answer 404; a malformed id answers 400")
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
