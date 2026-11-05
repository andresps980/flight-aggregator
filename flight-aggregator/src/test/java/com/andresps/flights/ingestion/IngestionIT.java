package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.Reconciliation.assertAggregationReconciles;
import static com.andresps.flights.ingestion.Reconciliation.assertProviderReconciles;
import static com.andresps.flights.ingestion.Reconciliation.assertReconciles;
import static com.andresps.flights.ingestion.RecordedGdsMock.ALPHA;
import static com.andresps.flights.ingestion.RecordedGdsMock.BETA;
import static com.andresps.flights.ingestion.RecordedGdsMock.DAYS;
import static com.andresps.flights.ingestion.RecordedGdsMock.GAMMA;
import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.andresps.flights.ingestion.RecordedGdsMock.feed;
import static com.andresps.flights.ingestion.RecordedGdsMock.ok;
import static com.andresps.flights.ingestion.RecordedGdsMock.request;
import static com.andresps.flights.ingestion.RecordedGdsMock.stats;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.DayAggregation;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionStatus;
import com.andresps.flights.ingestion.domain.PartitionFailure;
import com.andresps.flights.ingestion.domain.ProviderReport;
import com.andresps.flights.ingestion.infrastructure.adapter.out.memory.InMemoryDayAggregationStore;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The ingestion flow end to end inside the application: real adapters, resilience and use case, against WireMock
 * serving feeds recorded from gds-mock. Timeouts and backoffs are scaled down; the open-wait is long so that breaker
 * state can be observed across runs.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "aggregator.ingestion.max-concurrent-days=7",
            "aggregator.ingestion.max-concurrent-partitions=7",
            "aggregator.ingestion.run-timeout=30s",
            "aggregator.resilience.default.connect-timeout=500ms",
            "aggregator.resilience.default.read-timeout=1s",
            "aggregator.resilience.default.attempt-timeout=5s",
            "aggregator.resilience.default.retry.initial-backoff=10ms",
            "aggregator.resilience.default.circuit-breaker.open-wait=60s"
        })
class IngestionIT {

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

    @Autowired
    private InMemoryDayAggregationStore store;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        SERVER.resetAll();
        RecordedGdsMock.stubAll(SERVER);
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        store.clear();
    }

    private static int requests(String provider) {
        return SERVER.findAll(getRequestedFor(urlPathEqualTo(RecordedGdsMock.path(provider))))
                .size();
    }

    @Test
    @DisplayName("AC-22: with no failures, every figure reconciles exactly with gds-mock's /admin/world/stats")
    void reconcilesWithWorldStats() {
        IngestionReport report = ingestion.ingest();

        assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertReconciles(report, stats());
        DAYS.forEach(day -> assertAggregationReconciles(report.day(day).statistics(), stats(day)));
        assertThat(report.providers()).allSatisfy(p -> {
            assertThat(p.partitionsSucceeded()).isEqualTo(7);
            assertThat(p.attempts()).isEqualTo(7);
            assertThat(p.circuitState()).isEqualTo(CircuitState.CLOSED);
        });
    }

    @Test
    @DisplayName("AC-11/AC-22: every day is published with its deduplicated itineraries and all providers contributing")
    void publishesEveryDay() {
        IngestionReport report = ingestion.ingest();

        assertThat(store.days()).containsExactlyElementsOf(DAYS);
        long published = DAYS.stream()
                .map(store::day)
                .mapToLong(day -> day.orElseThrow().result().itineraries().size())
                .sum();
        assertThat(published).isEqualTo(report.statistics().itinerariesProduced());
        assertThat(store.day(DAYS.getFirst()).orElseThrow().missing()).isEmpty();
    }

    @Test
    @DisplayName("AC-12: a partition slower than read-timeout is retried and the run still reconciles exactly")
    void slowPartitionIsRetried() {
        LocalDate day = DAYS.getFirst();
        firstThen(GAMMA, day, ok(GAMMA, feed(GAMMA, day)).withFixedDelay(2000));

        IngestionReport report = ingestion.ingest();

        assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertThat(report.provider(new ProviderId(GAMMA)).attempts()).isEqualTo(8);
        assertReconciles(report, stats());
    }

    @Test
    @DisplayName("AC-14: a 500 then success is retried and the run still reconciles exactly")
    void serverErrorIsRetried() {
        firstThen(BETA, DAYS.get(2), aResponse().withStatus(500));

        IngestionReport report = ingestion.ingest();

        assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertThat(report.provider(new ProviderId(BETA)).attempts()).isEqualTo(8);
        assertReconciles(report, stats());
    }

    @Test
    @DisplayName(
            "AC-18: an attempt that fails mid-stream leaves no offers behind; the retry's are counted exactly once")
    void midStreamFailureLeavesNoPartialOffers() {
        LocalDate day = DAYS.get(1);
        byte[] full = feed(ALPHA, day);
        firstThen(ALPHA, day, ok(ALPHA, Arrays.copyOf(full, full.length / 2)));

        IngestionReport report = ingestion.ingest();

        assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertThat(report.provider(new ProviderId(ALPHA)).attempts()).isEqualTo(8);
        assertReconciles(report, stats());
    }

    @Test
    @DisplayName(
            "AC-15: a partition failing on every attempt fails alone; the provider is PARTIAL, the others COMPLETED")
    void persistentServerErrorFailsOnePartition() {
        LocalDate day = DAYS.get(2);
        SERVER.stubFor(request(BETA, day).willReturn(aResponse().withStatus(500)));

        IngestionReport report = ingestion.ingest();

        ProviderReport beta = report.provider(new ProviderId(BETA));
        assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(beta.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(beta.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.day()).isEqualTo(day);
            assertThat(failure.kind()).isEqualTo(FailureKind.SERVER_ERROR);
            assertThat(failure.attempts()).isEqualTo(3);
        });
        assertThat(beta.partitionsSucceeded()).isEqualTo(6);
        assertProviderReconciles(report.provider(new ProviderId(ALPHA)), stats());
        assertProviderReconciles(report.provider(new ProviderId(GAMMA)), stats());
        DayAggregation published = store.day(day).orElseThrow();
        assertThat(published.missing()).containsExactly(new ProviderId(BETA));
        assertAggregationReconciles(report.day(DAYS.getFirst()).statistics(), stats(DAYS.getFirst()));
    }

    @Test
    @DisplayName("AC-16/AC-19: a provider in outage (503) opens its breaker, is short-circuited and the run is PARTIAL")
    void providerOutage() {
        SERVER.stubFor(get(urlPathEqualTo(RecordedGdsMock.path(BETA)))
                .atPriority(1)
                .willReturn(aResponse().withStatus(503)));

        IngestionReport report = ingestion.ingest();

        ProviderReport beta = report.provider(new ProviderId(BETA));
        assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(beta.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(beta.circuitState()).isEqualTo(CircuitState.OPEN);
        assertThat(beta.failures()).extracting(PartitionFailure::kind).contains(FailureKind.CIRCUIT_OPEN);
        assertThat(requests(BETA))
                .as("short-circuited: fewer than 7 partitions x 3 attempts")
                .isLessThan(21);
        assertThat(report.provider(new ProviderId(ALPHA)).status()).isEqualTo(IngestionStatus.COMPLETED);
        assertProviderReconciles(report.provider(new ProviderId(ALPHA)), stats());
        assertProviderReconciles(report.provider(new ProviderId(GAMMA)), stats());
        assertThat(store.days()).containsExactlyElementsOf(DAYS);
        assertThat(store.day(DAYS.getFirst()).orElseThrow().missing()).containsExactly(new ProviderId(BETA));
    }

    @Test
    @DisplayName("AC-16: breakers live across runs: the next run starts short-circuited and makes no request")
    void openBreakerCarriesOverToNextRun() {
        SERVER.stubFor(get(urlPathEqualTo(RecordedGdsMock.path(BETA)))
                .atPriority(1)
                .willReturn(aResponse().withStatus(503)));
        ingestion.ingest();
        int afterFirstRun = requests(BETA);

        IngestionReport second = ingestion.ingest();

        assertThat(requests(BETA)).isEqualTo(afterFirstRun);
        assertThat(second.provider(new ProviderId(BETA)).failures())
                .extracting(PartitionFailure::kind)
                .containsOnly(FailureKind.CIRCUIT_OPEN);
    }

    @Test
    @DisplayName("AC-20: every provider down returns a FAILED report, publishes nothing and throws no exception")
    void allProvidersDown() {
        PROVIDERS.forEach(p -> SERVER.stubFor(get(urlPathEqualTo(RecordedGdsMock.path(p)))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500))));

        IngestionReport report = ingestion.ingest();

        assertThat(report.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(report.providers()).allSatisfy(p -> assertThat(p.status()).isEqualTo(IngestionStatus.FAILED));
        assertThat(store.days()).isEmpty();
        assertThat(report.statistics().totalReceived()).isZero();
    }

    private static void firstThen(
            String provider, LocalDate day, com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder first) {
        String scenario = provider + day;
        SERVER.stubFor(request(provider, day)
                .atPriority(1)
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(first)
                .willSetStateTo("recovered"));
        SERVER.stubFor(request(provider, day)
                .atPriority(1)
                .inScenario(scenario)
                .whenScenarioStateIs("recovered")
                .willReturn(ok(provider, feed(provider, day))));
    }
}
