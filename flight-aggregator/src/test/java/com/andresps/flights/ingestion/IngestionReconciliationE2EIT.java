package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.Reconciliation.assertAggregationReconciles;
import static com.andresps.flights.ingestion.Reconciliation.assertReconciles;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.application.port.in.PublicationGuard;
import com.andresps.flights.ingestion.domain.DayReport;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionStatus;
import com.andresps.flights.support.TestPostgres;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-22 end to end: a real ingestion against a <b>running</b> gds-mock in the {@code deterministic} profile, reconciled
 * with its live {@code GET /admin/world/stats}. Excluded from the default build (tag {@code e2e}). Run it with:
 *
 * <pre>
 * ./mvnw -pl gds-mock spring-boot:run -Dspring-boot.run.profiles=deterministic
 * ./mvnw -pl flight-aggregator verify -Pe2e-tests [-De2e.gds-mock.url=http://localhost:8081]
 * </pre>
 *
 * Not against the default {@code docker compose up}: it runs chaos, which {@link #mockIsDeterministic()} rejects.
 * {@code scripts/stack-smoke.sh} (spec 007) runs this test against a deterministic compose stack.
 *
 * Spec 008 AC-16: the rates come from gds-mock's own {@code /fx/latest}, so this reconciles whatever currencies the
 * mock's providers are configured with (the smoke test's compose override mixes EUR, USD and GBP per record).
 *
 * Both sides use today's UTC date as reference date, so don't run it across midnight UTC.
 */
@Tag("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestPostgres.class)
class IngestionReconciliationE2EIT {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyTables() {
        TestPostgres.truncateAll(dataSource);
    }

    private static final String MOCK_URL = System.getProperty("e2e.gds-mock.url", "http://localhost:8081");
    private static final RestClient MOCK = RestClient.create(MOCK_URL);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        RecordedGdsMock.PROVIDERS.forEach(p -> registry.add("aggregator.providers." + p + ".base-url", () -> MOCK_URL));
        // spec 008 AC-16: gds-mock's Frankfurter-compatible FX double, whatever currencies its providers price in
        registry.add("aggregator.exchange-rates.base-url", () -> MOCK_URL + "/fx");
    }

    @BeforeAll
    static void mockIsDeterministic() {
        JsonNode providers = get("/admin/providers");
        assertThat(providers.valueStream())
                .as("gds-mock at %s must run the deterministic profile (no latency, no errors, no outage)", MOCK_URL)
                .allSatisfy(p -> {
                    assertThat(p.path("latencyMaxMs").asLong()).isZero();
                    assertThat(p.path("errorRate").asDouble()).isZero();
                    assertThat(p.path("outage").asBoolean()).isFalse();
                });
    }

    @Autowired
    private IngestFlightsUseCase ingestion;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("AC-22/AC-16: a real ingestion reconciles exactly with the running gds-mock's /admin/world/stats, "
            + "whatever currencies its providers price in")
    void reconcilesWithRunningMock() {
        JsonNode stats = get("/admin/world/stats");
        assertThat(LocalDate.parse(stats.path("referenceDate").asString()))
                .as("same reference date on both sides")
                .isEqualTo(LocalDate.now(clock));

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

        assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertReconciles(report, stats);
        for (DayReport day : report.days()) {
            assertAggregationReconciles(day.statistics(), get("/admin/world/stats?date=" + day.day()));
        }
    }

    private static JsonNode get(String path) {
        return JSON.readTree(MOCK.get().uri(path).retrieve().body(String.class));
    }
}
