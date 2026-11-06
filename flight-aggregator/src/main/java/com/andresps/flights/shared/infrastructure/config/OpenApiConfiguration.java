package com.andresps.flights.shared.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Title and version of the OpenAPI description served at {@code /v3/api-docs} (Swagger UI at {@code /swagger-ui.html}).
 * Which paths it covers is set by {@code springdoc.paths-to-match}; the committed copy is {@code docs/api/openapi.json}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI flightAggregatorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Flight Aggregator search API")
                        .version("v1")
                        .description("Search over the aggregated itineraries (spec 005). Errors are RFC 9457 "
                                + "problem details."));
    }
}
