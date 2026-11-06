package com.andresps.flights.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.DayOutcome;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.IngestionStatus;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import com.andresps.flights.ingestion.domain.RunStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spec 005 AC-24: the OpenAPI description published by the application ({@code /v3/api-docs}) is the one committed in
 * {@code docs/api/openapi.json}, so the frontend and reviewers read the real contract. Spec 006 (backend follow-up) adds
 * the ingestion endpoints and exact required/nullable properties, because the dashboard generates its TypeScript types
 * from it.
 *
 * <p>In {@code ./mvnw verify} and CI this test only compares, and fails on any drift. Regenerating the file is an
 * explicit action: {@code ./mvnw -pl flight-aggregator verify -Popenapi-write} writes it and reports this test as
 * aborted (never as passed, so a regeneration cannot look like a green contract check); it is refused when {@code CI}
 * is set. Then review the diff and run {@code npm run gen:api} in {@code frontend/}.
 */
class OpenApiContractIT extends SearchSpringTest {

    private static final Path COMMITTED = Path.of("..", "docs", "api", "openapi.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OUT_OF_DATE = "docs/api/openapi.json is out of date: regenerate it with "
            + "./mvnw -pl flight-aggregator verify -Popenapi-write, review the diff, then npm run gen:api in frontend/";

    private JsonNode published() {
        ObjectNode spec = (ObjectNode) get("/v3/api-docs").getBody();
        spec.remove("servers"); // carries the random test port
        return spec;
    }

    /** The comparison every build makes; a separate method so a test can prove that a drift fails it. */
    static void assertSameContract(JsonNode committed, JsonNode published) {
        assertThat(committed).as(OUT_OF_DATE).isEqualTo(published);
    }

    @Test
    @DisplayName("AC-24: the published OpenAPI description equals docs/api/openapi.json")
    void matchesTheCommittedContract() throws IOException {
        JsonNode published = published();
        if (Boolean.getBoolean("openapi.write")) {
            assertThat(System.getenv("CI"))
                    .as("-Popenapi-write regenerates the contract; it must never run in CI")
                    .isNull();
            Files.writeString(COMMITTED, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(published) + "\n");
            Assumptions.abort(
                    "docs/api/openapi.json regenerated (not compared): review the diff, run npm run gen:api in "
                            + "frontend/, then ./mvnw verify");
        }

        assertThat(COMMITTED).exists();
        assertSameContract(JSON.readTree(Files.readString(COMMITTED)), published);
    }

    @Test
    @DisplayName("AC-24: a committed description that differs from the published one fails the build")
    void driftFailsTheBuild() {
        JsonNode published = published();
        ObjectNode drifted = (ObjectNode) published.deepCopy();
        ((ObjectNode) drifted.at("/components/schemas/IngestionRunResponse/properties/status"))
                .put("description", "edited by hand");

        assertThatThrownBy(() -> assertSameContract(drifted, published))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("out of date")
                .hasMessageContaining("-Popenapi-write");
    }

    @Test
    @DisplayName("AC-24: search and catalog are described with parameter constraints and the 400 ProblemDetail")
    void describesTheSearchApi() {
        JsonNode spec = published();
        JsonNode search = spec.at("/paths/~1api~1v1~1itineraries/get");
        JsonNode catalog = spec.at("/paths/~1api~1v1~1catalog/get");

        assertThat(search.isMissingNode()).isFalse();
        assertThat(catalog.isMissingNode()).isFalse();
        assertThat(search.at("/responses/200").isMissingNode()).isFalse();
        assertThat(search.at("/responses/400/content/application~1problem+json").isMissingNode())
                .isFalse();

        JsonNode parameters = search.get("parameters");
        assertThat(parameters.findValuesAsString("name"))
                .containsExactlyInAnyOrder("origin", "destination", "date", "maxPrice", "carrier", "limit", "cursor");
        for (JsonNode parameter : parameters) {
            assertThat(parameter.get("description").asString())
                    .as(parameter.get("name").asString())
                    .isNotBlank();
            String name = parameter.get("name").asString();
            JsonNode schema = parameter.get("schema");
            switch (name) {
                case "origin", "destination" ->
                    assertThat(schema.get("pattern").asString()).contains("3");
                case "carrier" -> assertThat(schema.get("pattern").asString()).contains("2");
                case "date" -> assertThat(schema.get("format").asString()).isEqualTo("date");
                case "limit" -> {
                    assertThat(schema.get("minimum").asInt()).isEqualTo(1);
                    assertThat(schema.get("maximum").asInt()).isEqualTo(100);
                    assertThat(schema.get("default").asInt()).isEqualTo(20);
                }
                case "maxPrice" -> assertThat(schema.get("minimum").asInt()).isZero();
                default -> {}
            }
        }
        assertThat(spec.at("/components/schemas/ItineraryPageResponse/properties/hasMore")
                        .isMissingNode())
                .isFalse();
        assertThat(spec.at("/components/schemas/CatalogResponse/properties/routes")
                        .isMissingNode())
                .isFalse();
        assertThat(spec.at("/components/schemas/ItineraryPageResponse/properties/nextCursor/description")
                        .asString())
                .contains("shorter than");
    }

    @Test
    @DisplayName("spec 006: ingestion endpoints are described with 202 + Location, 409 problem, 200/400/404")
    void describesTheIngestionApi() {
        JsonNode spec = published();
        JsonNode post = spec.at("/paths/~1api~1v1~1ingestions/post");
        JsonNode latest = spec.at("/paths/~1api~1v1~1ingestions~1latest/get");
        JsonNode byId = spec.at("/paths/~1api~1v1~1ingestions~1{id}/get");

        assertThat(post.get("responses").propertyNames()).containsExactlyInAnyOrder("202", "409");
        assertThat(post.at("/responses/202/headers/Location").isMissingNode()).isFalse();
        assertThat(post.at("/responses/202/content/application~1json/schema/$ref")
                        .asString())
                .endsWith("/IngestionRunResponse");
        assertThat(post.at("/responses/409/content/application~1problem+json/schema/$ref")
                        .asString())
                .endsWith("/IngestionAlreadyRunningProblem");
        assertThat(spec.at("/components/schemas/IngestionAlreadyRunningProblem/properties/runningIngestionId")
                        .isMissingNode())
                .isFalse();

        assertThat(latest.get("responses").propertyNames()).containsExactlyInAnyOrder("200", "404");
        assertThat(byId.get("responses").propertyNames()).containsExactlyInAnyOrder("200", "400", "404");
        assertThat(latest.at("/responses/404/content/application~1problem+json").isMissingNode())
                .isFalse();
    }

    @Test
    @DisplayName("spec 006: documented enumerations match the Java enums they are written from")
    void enumerationsMatchTheCode() {
        JsonNode schemas = published().at("/components/schemas");

        assertThat(enumOf(schemas, "IngestionRunResponse", "status")).isEqualTo(names(RunStatus.values()));
        assertThat(enumOf(schemas, "IngestionRunResponse", "trigger")).isEqualTo(names(IngestionTrigger.values()));
        assertThat(enumOf(schemas, "ReportDocument", "status")).isEqualTo(names(IngestionStatus.values()));
        assertThat(enumOf(schemas, "ProviderDocument", "status")).isEqualTo(names(IngestionStatus.values()));
        assertThat(enumOf(schemas, "ProviderDocument", "circuitState")).isEqualTo(names(CircuitState.values()));
        assertThat(enumOf(schemas, "FailureDocument", "kind")).isEqualTo(names(FailureKind.values()));
        assertThat(enumOf(schemas, "DayDocument", "outcome")).isEqualTo(names(DayOutcome.values()));
    }

    @Test
    @DisplayName("spec 006: response properties are required (nullable where null), ProblemDetail is flat")
    void responsePropertiesAreExact() {
        JsonNode schemas = published().at("/components/schemas");

        for (String name :
                List.of("ItineraryPageResponse", "ItineraryResponse", "IngestionRunResponse", "DayDocument")) {
            assertThat(schemas.at("/" + name + "/required").valueStream().map(JsonNode::asString))
                    .as(name)
                    .containsExactlyInAnyOrderElementsOf(
                            schemas.at("/" + name + "/properties").propertyNames());
        }
        assertThat(schemas.at("/IngestionRunResponse/properties/finishedAt/type")
                        .valueStream()
                        .map(JsonNode::asString))
                .contains("null");
        assertThat(schemas.at("/IngestionRunResponse/properties/report/anyOf/0/$ref")
                        .asString())
                .endsWith("/ReportDocument");
        assertThat(schemas.at("/IngestionRunResponse/properties/report/anyOf/1/type")
                        .asString())
                .isEqualTo("null");
        assertThat(schemas.at("/IngestionRunResponse/properties/report/$ref").isMissingNode())
                .isTrue();
        assertThat(schemas.at("/ProblemDetail/properties/properties").isMissingNode())
                .isTrue();
        assertThat(schemas.at("/IngestionAlreadyRunningProblem/required")
                        .valueStream()
                        .map(JsonNode::asString))
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance");
    }

    private static List<String> enumOf(JsonNode schemas, String schema, String property) {
        return schemas.at("/" + schema + "/properties/" + property + "/enum")
                .valueStream()
                .map(JsonNode::asString)
                .toList();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
