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
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.application.port.in.PublicationGuard;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionStatus;
import com.andresps.flights.ingestion.domain.PartitionFailure;
import com.andresps.flights.ingestion.domain.ProviderReport;
import com.andresps.flights.support.StoredData;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ingestion flow end to end inside the application: real adapters, resilience and use case, against WireMock
 * serving feeds recorded from gds-mock (shared setup in {@link RecordedFeedsSpringTest}).
 */
class IngestionIT extends RecordedFeedsSpringTest {

    @Autowired
    private IngestFlightsUseCase ingestion;

    private StoredData store;

    @BeforeEach
    void storedData() {
        store = new StoredData(dataSource);
    }

    private static int requests(String provider) {
        return SERVER.findAll(getRequestedFor(urlPathEqualTo(RecordedGdsMock.path(provider))))
                .size();
    }

    @Test
    @DisplayName("AC-22: with no failures, every figure reconciles exactly with gds-mock's /admin/world/stats")
    void reconcilesWithWorldStats() {
        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

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
        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

        assertThat(store.days()).containsExactlyElementsOf(DAYS);
        long published = DAYS.stream().mapToLong(store::rows).sum();
        assertThat(published).isEqualTo(report.statistics().itinerariesProduced());
        assertThat(store.snapshot(DAYS.getFirst()).orElseThrow().missingProviders())
                .isEmpty();
    }

    @Test
    @DisplayName("AC-12: a partition slower than read-timeout is retried and the run still reconciles exactly")
    void slowPartitionIsRetried() {
        LocalDate day = DAYS.getFirst();
        firstThen(GAMMA, day, ok(GAMMA, feed(GAMMA, day)).withFixedDelay(2000));

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

        assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertThat(report.provider(new ProviderId(GAMMA)).attempts()).isEqualTo(8);
        assertReconciles(report, stats());
    }

    @Test
    @DisplayName("AC-14: a 500 then success is retried and the run still reconciles exactly")
    void serverErrorIsRetried() {
        firstThen(BETA, DAYS.get(2), aResponse().withStatus(500));

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

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

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

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

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

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
        assertThat(store.snapshot(day).orElseThrow().missingProviders()).containsExactly(BETA);
        assertAggregationReconciles(report.day(DAYS.getFirst()).statistics(), stats(DAYS.getFirst()));
    }

    @Test
    @DisplayName("AC-16/AC-19: a provider in outage (503) opens its breaker, is short-circuited and the run is PARTIAL")
    void providerOutage() {
        SERVER.stubFor(get(urlPathEqualTo(RecordedGdsMock.path(BETA)))
                .atPriority(1)
                .willReturn(aResponse().withStatus(503)));

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

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
        assertThat(store.snapshot(DAYS.getFirst()).orElseThrow().missingProviders())
                .containsExactly(BETA);
    }

    @Test
    @DisplayName("AC-16: breakers live across runs: the next run starts short-circuited and makes no request")
    void openBreakerCarriesOverToNextRun() {
        SERVER.stubFor(get(urlPathEqualTo(RecordedGdsMock.path(BETA)))
                .atPriority(1)
                .willReturn(aResponse().withStatus(503)));
        ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);
        int afterFirstRun = requests(BETA);

        IngestionReport second = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

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

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

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
