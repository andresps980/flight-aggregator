package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.RecordedGdsMock.ALPHA;
import static com.andresps.flights.ingestion.RecordedGdsMock.BETA;
import static com.andresps.flights.ingestion.RecordedGdsMock.DAYS;
import static com.andresps.flights.ingestion.RecordedGdsMock.GAMMA;
import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.andresps.flights.ingestion.RecordedGdsMock.feed;
import static com.andresps.flights.ingestion.RecordedGdsMock.ok;
import static com.andresps.flights.ingestion.RecordedGdsMock.request;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.application.port.in.PublicationGuard;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionStatus;
import com.andresps.flights.support.TestPostgres;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
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
 * AC-9: the providers of a day are queried at the same time. One horizon day, three providers answering after fixed
 * latencies of 2 s, 1.5 s and 1 s: the run takes about the slowest (2 s), not the sum (4.5 s). The upper bound is
 * loose (3.5 s) to stay deterministic on a slow CI machine; the barrier tests in IngestionServiceTest prove the
 * concurrency itself.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "aggregator.ingestion.horizon-days=1",
            "aggregator.resilience.default.read-timeout=3s",
            "aggregator.resilience.default.attempt-timeout=10s"
        })
@Import(TestPostgres.class)
class IngestionParallelismIT {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyTables() {
        TestPostgres.truncateAll(dataSource);
    }

    private static final Map<String, Integer> LATENCY_MS = Map.of(ALPHA, 2000, BETA, 1500, GAMMA, 1000);
    private static final WireMockServer SERVER = startServer();

    private static WireMockServer startServer() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        LocalDate day = DAYS.getFirst();
        LATENCY_MS.forEach((provider, latency) -> server.stubFor(request(provider, day)
                .willReturn(ok(provider, feed(provider, day)).withFixedDelay(latency))));
        return server;
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        PROVIDERS.forEach(p -> registry.add("aggregator.providers." + p + ".base-url", SERVER::baseUrl));
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
    @DisplayName("AC-9: three providers with 2 s / 1.5 s / 1 s latency take about the slowest, not the 4.5 s sum")
    void providersRunInParallel() {
        long start = System.nanoTime();
        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(2000)).isLessThan(Duration.ofMillis(3500));
    }
}
