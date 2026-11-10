package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.Reconciliation.assertProviderReconciles;
import static com.andresps.flights.ingestion.RecordedGdsMock.BETA;
import static com.andresps.flights.ingestion.RecordedGdsMock.DAYS;
import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.andresps.flights.ingestion.RecordedGdsMock.feed;
import static com.andresps.flights.ingestion.RecordedGdsMock.ok;
import static com.andresps.flights.ingestion.RecordedGdsMock.request;
import static com.andresps.flights.ingestion.RecordedGdsMock.stats;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.application.port.in.PublicationGuard;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionStatus;
import com.andresps.flights.ingestion.domain.PartitionFailure;
import com.andresps.flights.support.TestPostgres;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AC-13 over real HTTP: Mock-Beta answers after 5 s, the run deadline is 1 s and the timeouts are generous, so only
 * the run deadline can end Beta's requests. The run must return close to the deadline, report Beta as RUN_TIMEOUT,
 * keep the other providers' data, and never start a request after the deadline.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "aggregator.ingestion.run-timeout=1s",
            "aggregator.ingestion.max-concurrent-days=7",
            "aggregator.ingestion.max-concurrent-partitions=2",
            "aggregator.resilience.default.read-timeout=10s",
            "aggregator.resilience.default.attempt-timeout=20s"
        })
@Import(TestPostgres.class)
class IngestionRunDeadlineIT {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyTables() {
        TestPostgres.truncateAll(dataSource);
    }

    private static final WireMockServer SERVER = startServer();

    private static WireMockServer startServer() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        RecordedGdsMock.stubExchangeRates(server); // spec 008: a run reads the rates first; no retries on a dead URL
        RecordedGdsMock.stubAll(server);
        DAYS.forEach(day -> server.stubFor(request(BETA, day)
                .atPriority(1)
                .willReturn(ok(BETA, feed(BETA, day)).withFixedDelay(5000))));
        return server;
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        PROVIDERS.forEach(p -> registry.add("aggregator.providers." + p + ".base-url", SERVER::baseUrl));
        registry.add("aggregator.exchange-rates.base-url", () -> SERVER.baseUrl() + RecordedGdsMock.FX_BASE_PATH);
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock clock() {
            return RecordedGdsMock.CLOCK;
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @Autowired
    private IngestFlightsUseCase ingestion;

    @Test
    @DisplayName("AC-13: at the run deadline Beta's requests are aborted as RUN_TIMEOUT; no request starts afterwards")
    void runDeadlineAbortsRealRequests() throws InterruptedException {
        long start = System.nanoTime();
        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        int betaRequests = betaRequests();
        Thread.sleep(300);

        assertThat(elapsed)
                .as("returns close to the 1 s deadline, not after 5 s")
                .isLessThan(Duration.ofSeconds(3));
        assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(report.provider(new ProviderId(BETA)).failures())
                .extracting(PartitionFailure::kind)
                .containsOnly(FailureKind.RUN_TIMEOUT)
                .hasSize(7);
        assertThat(betaRequests)
                .as("only the 2 allowed in flight were ever started")
                .isLessThanOrEqualTo(2);
        assertThat(betaRequests()).as("nothing started after the run returned").isEqualTo(betaRequests);
        assertProviderReconciles(report.provider(new ProviderId(RecordedGdsMock.ALPHA)), stats());
        assertProviderReconciles(report.provider(new ProviderId(RecordedGdsMock.GAMMA)), stats());
    }

    private static int betaRequests() {
        return SERVER.findAll(getRequestedFor(urlPathEqualTo(RecordedGdsMock.path(BETA))))
                .size();
    }
}
