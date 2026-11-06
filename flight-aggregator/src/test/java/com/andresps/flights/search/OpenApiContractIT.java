package com.andresps.flights.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spec 005 AC-24: the OpenAPI description published by the application ({@code /v3/api-docs}) is the one committed in
 * {@code docs/api/openapi.json}, so the frontend and reviewers read the real contract. After an intended API change,
 * regenerate it with {@code -Dopenapi.write=true} and review the diff.
 */
class OpenApiContractIT extends SearchSpringTest {

    private static final Path COMMITTED = Path.of("..", "docs", "api", "openapi.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JsonNode published() {
        ObjectNode spec = (ObjectNode) get("/v3/api-docs").getBody();
        spec.remove("servers"); // carries the random test port
        return spec;
    }

    @Test
    @DisplayName("AC-24: the published OpenAPI description equals docs/api/openapi.json")
    void matchesTheCommittedContract() throws IOException {
        JsonNode published = published();
        if (Boolean.getBoolean("openapi.write")) {
            Files.writeString(COMMITTED, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(published) + "\n");
        }

        assertThat(COMMITTED).exists();
        assertThat(JSON.readTree(Files.readString(COMMITTED)))
                .as("docs/api/openapi.json is out of date: run the IT with -Dopenapi.write=true and review the diff")
                .isEqualTo(published);
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
}
