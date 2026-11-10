package com.andresps.flights.ingestion;

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
import com.andresps.flights.support.TestPostgres;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spec 008 end to end on the feeds recorded from gds-mock: every provider's records re-priced round-robin in EUR, USD
 * and GBP, at 2 units per EUR (the FX double's rates), so every converted price is exact and the published read model
 * must equal the all-EUR one.
 */
class CurrencyIngestionIT extends RecordedFeedsSpringTest {

    private static final String[] CURRENCIES = {"EUR", "USD", "GBP"};
    private static final Pattern ALPHA_PRICE = Pattern.compile("\"totFare\":([^,]*),\"cur\":\"EUR\"");
    private static final Pattern BETA_PRICE = Pattern.compile("<totalAmount currency=\"EUR\">([^<]*)</totalAmount>");
    private static final Pattern GAMMA_PRICE = Pattern.compile("(?m)^(GAM-[^,]*),([^,]*),EUR,");

    @Autowired
    private IngestFlightsUseCase ingestion;

    private Map<String, String> readModel() {
        return new JdbcTemplate(dataSource)
                        .query(
                                "SELECT itinerary_key, base_price, final_price, selected_provider FROM itinerary",
                                (rs, i) -> Map.entry(
                                        rs.getString(1),
                                        rs.getBigDecimal(2).toPlainString() + "/"
                                                + rs.getBigDecimal(3).toPlainString() + "/" + rs.getString(4)))
                        .stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The amount in {@code currency} at 2 units per EUR; a malformed amount stays as it is. */
    private static String in(String currency, String eurAmount) {
        if (currency.equals("EUR")) {
            return eurAmount;
        }
        try {
            return new BigDecimal(eurAmount.strip()).multiply(BigDecimal.TWO).toPlainString();
        } catch (NumberFormatException e) {
            return eurAmount;
        }
    }

    /** Rewrites every price match, cycling EUR → USD → GBP, and checks that all three currencies were used. */
    private static byte[] mixed(byte[] recorded, Pattern price, Function<String[], String> render) {
        AtomicInteger next = new AtomicInteger();
        String converted = price.matcher(new String(recorded, StandardCharsets.UTF_8))
                .replaceAll(m -> {
                    String currency = CURRENCIES[next.getAndIncrement() % CURRENCIES.length];
                    String[] groups = new String[m.groupCount() + 1];
                    for (int g = 1; g <= m.groupCount(); g++) {
                        groups[g] = m.group(g);
                    }
                    groups[0] = currency;
                    return Matcher.quoteReplacement(render.apply(groups));
                });
        assertThat(next.get()).as("records re-priced").isGreaterThanOrEqualTo(CURRENCIES.length);
        return converted.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] mixedFeed(String provider, LocalDate day) {
        return switch (provider) {
            case ALPHA ->
                mixed(
                        feed(ALPHA, day),
                        ALPHA_PRICE,
                        g -> "\"totFare\":" + in(g[0], g[1]) + ",\"cur\":\"" + g[0] + "\"");
            case BETA ->
                mixed(
                        feed(BETA, day),
                        BETA_PRICE,
                        g -> "<totalAmount currency=\"" + g[0] + "\">" + in(g[0], g[1]) + "</totalAmount>");
            case GAMMA -> mixed(feed(GAMMA, day), GAMMA_PRICE, g -> g[1] + "," + in(g[0], g[2]) + "," + g[0] + ",");
            default -> throw new IllegalArgumentException(provider);
        };
    }

    private static void stubMixedCurrencies() {
        PROVIDERS.forEach(provider -> DAYS.forEach(day ->
                SERVER.stubFor(request(provider, day).willReturn(ok(provider, mixedFeed(provider, day))))));
    }

    @Test
    @DisplayName("AC-16/AC-1/AC-2/AC-5: feeds mixing EUR, USD and GBP per record publish exactly the all-EUR "
            + "read model and reconcile with gds-mock's stats")
    void mixedCurrenciesPublishTheSameReadModel() {
        IngestionReport eurReport = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);
        Map<String, String> eurReadModel = readModel();
        TestPostgres.truncateAll(dataSource);
        stubMixedCurrencies();

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

        assertThat(eurReport.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertReconciles(report, stats());
        assertThat(readModel()).hasSize(eurReadModel.size()).isEqualTo(eurReadModel);
    }

    @Test
    @DisplayName("AC-9: one run requests the FX API exactly once")
    void ratesOncePerRun() {
        stubMixedCurrencies();

        ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

        assertThat(SERVER.findAll(getRequestedFor(urlPathEqualTo(RecordedGdsMock.FX_BASE_PATH + "/latest"))))
                .hasSize(1);
    }

    @Test
    @DisplayName("AC-10: with the FX API down, partitions holding a non-EUR price fail as "
            + "EXCHANGE_RATES_UNAVAILABLE after one attempt, without opening a breaker; EUR-only feeds are ingested")
    void fxDown() {
        DAYS.forEach(day -> SERVER.stubFor(request(BETA, day).willReturn(ok(BETA, mixedFeed(BETA, day)))));
        SERVER.stubFor(
                RecordedGdsMock.exchangeRatesRequest().willReturn(aResponse().withStatus(503)));

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

        assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(report.provider(new ProviderId(ALPHA)).status()).isEqualTo(IngestionStatus.COMPLETED);
        assertThat(report.provider(new ProviderId(GAMMA)).status()).isEqualTo(IngestionStatus.COMPLETED);
        ProviderReport beta = report.provider(new ProviderId(BETA));
        assertThat(beta.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(beta.failures())
                .hasSize(DAYS.size())
                .extracting(PartitionFailure::kind)
                .containsOnly(FailureKind.EXCHANGE_RATES_UNAVAILABLE);
        assertThat(beta.attempts()).isEqualTo(DAYS.size());
        assertThat(beta.circuitState()).isEqualTo(CircuitState.CLOSED);
        assertThat(new StoredData(dataSource)
                        .snapshot(DAYS.getFirst())
                        .orElseThrow()
                        .missingProviders())
                .containsExactly(BETA);
    }

    @Test
    @DisplayName("AC-10: with the FX API down and every provider mixing currencies, the run fails and each day keeps "
            + "its previous snapshot, marked stale, with the same itineraries")
    void fxDownKeepsPreviousSnapshots() {
        IngestionReport first = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);
        Map<String, String> before = readModel();
        stubMixedCurrencies();
        SERVER.stubFor(
                RecordedGdsMock.exchangeRatesRequest().willReturn(aResponse().withStatus(503)));

        IngestionReport report = ingestion.ingest(UUID.randomUUID(), PublicationGuard.ALWAYS);

        assertThat(first.status()).isEqualTo(IngestionStatus.COMPLETED);
        assertThat(report.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(report.providers())
                .allSatisfy(provider -> assertThat(provider.failures())
                        .extracting(PartitionFailure::kind)
                        .containsOnly(FailureKind.EXCHANGE_RATES_UNAVAILABLE));
        StoredData store = new StoredData(dataSource);
        assertThat(DAYS).allSatisfy(day -> {
            StoredData.DayRow row = store.snapshot(day).orElseThrow();
            assertThat(row.runId()).isEqualTo(first.runId());
            assertThat(row.staleSince()).isNotNull();
        });
        assertThat(readModel()).isEqualTo(before);
    }
}
