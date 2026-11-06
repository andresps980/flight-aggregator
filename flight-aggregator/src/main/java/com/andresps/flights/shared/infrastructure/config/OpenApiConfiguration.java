package com.andresps.flights.shared.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Title and version of the OpenAPI description served at {@code /v3/api-docs} (Swagger UI at {@code /swagger-ui.html}).
 * Which paths it covers is set by {@code springdoc.paths-to-match}; the committed copy is {@code docs/api/openapi.json},
 * from which the dashboard generates its TypeScript types (spec 006).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI flightAggregatorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Flight Aggregator API")
                        .version("v1")
                        .description("Search over the aggregated itineraries (spec 005) and ingestion runs (spec 004). "
                                + "Errors are RFC 9457 problem details."));
    }

    /**
     * Makes the description say what the JSON really contains, so generated clients get exact types:
     *
     * <ul>
     *   <li>Every property of a response record ({@code *Response}, report {@code *Document}) is required: Jackson
     *       writes every record component, {@code null} included. Properties that can be {@code null} are marked
     *       {@code nullable} on the record, so a client sees {@code string | null}, never an optional field.
     *   <li>{@code ProblemDetail}: Spring writes extra properties (e.g. {@code runningIngestionId}) next to the
     *       standard fields, not inside a {@code properties} object as springdoc infers from the getter.
     * </ul>
     */
    @Bean
    OpenApiCustomizer exactResponseSchemas() {
        return openApi -> {
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            schemas.forEach((name, schema) -> {
                if ((name.endsWith("Response") || name.endsWith("Document")) && schema.getProperties() != null) {
                    schema.setRequired(
                            new ArrayList<String>(schema.getProperties().keySet()));
                }
            });
            Schema<?> problem = schemas.get("ProblemDetail");
            if (problem != null && problem.getProperties() != null) {
                problem.getProperties().remove("properties");
                problem.setAdditionalProperties(true);
            }
        };
    }
}
