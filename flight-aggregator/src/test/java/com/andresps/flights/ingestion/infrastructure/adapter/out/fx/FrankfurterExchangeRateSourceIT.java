package com.andresps.flights.ingestion.infrastructure.adapter.out.fx;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.ExchangeRatesUnavailableException;
import com.andresps.flights.aggregation.domain.model.InvalidFlightDataException;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.RetrySettings;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec 008: the Frankfurter adapter against a WireMock FX API. No Spring context: built as the composition root builds
 * it. Timeouts no slow runner can hit, except in the timeout test (CLAUDE.md, testing conventions).
 */
class FrankfurterExchangeRateSourceIT {

    private static final String PATH = "/v1/latest";
    private static final String BODY = """
            {"amount":1.0,"base":"EUR","date":"2026-11-09","rates":{"GBP":0.8343,"USD":1.1193}}""";
    private static final RetrySettings RETRY = new RetrySettings(3, Duration.ofMillis(10), 2.0, 0.5);
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency GBP = Currency.getInstance("GBP");

    private static WireMockServer server;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    private static FrankfurterExchangeRateSource source(Duration readTimeout) {
        return new FrankfurterExchangeRateSource(
                URI.create(server.baseUrl() + "/v1"),
                Duration.ofSeconds(5),
                readTimeout,
                Duration.ofSeconds(10),
                RETRY);
    }

    private static FrankfurterExchangeRateSource source() {
        return source(Duration.ofSeconds(5));
    }

    private static void always(ResponseDefinitionBuilder response) {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(response));
    }

    private static int requests() {
        return server.findAll(getRequestedFor(urlPathEqualTo(PATH))).size();
    }

    @Test
    @DisplayName("AC-9: requests {base-url}/latest?base=EUR once and returns its date and rates")
    void fetchesLatestRates() {
        always(okJson(BODY));

        ExchangeRates rates = source().latest();

        assertThat(rates.available()).isTrue();
        assertThat(rates.date()).contains(LocalDate.parse("2026-11-09"));
        assertThat(rates.toEur(new BigDecimal("111.93"), USD)).isEqualTo(Money.eur("100.00"));
        assertThat(rates.toEur(new BigDecimal("83.43"), GBP)).isEqualTo(Money.eur("100.00"));
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("base", equalTo("EUR")));
    }

    @Test
    @DisplayName("AC-11: rate entries whose code is not ISO 4217 are ignored; EUR always converts at 1")
    void ignoresUnknownCodes() {
        always(okJson("""
                {"amount":1.0,"base":"EUR","date":"2026-11-09","rates":{"ZZZ":3,"usd":2,"USD":1.25}}"""));

        ExchangeRates rates = source().latest();

        assertThat(rates.toEur(new BigDecimal("10"), USD)).isEqualTo(Money.eur("8.00"));
        assertThat(rates.toEur(new BigDecimal("10"), Currency.getInstance("EUR")))
                .isEqualTo(Money.eur("10.00"));
        assertThatThrownBy(() -> rates.toEur(BigDecimal.ONE, Currency.getInstance("XAF")))
                .isInstanceOf(InvalidFlightDataException.class);
    }

    @Test
    @DisplayName("AC-11: an EUR entry other than 1 in the body is ignored: EUR is the base and converts at 1")
    void eurEntryIgnored() {
        always(okJson("""
                {"amount":1.0,"base":"EUR","date":"2026-11-09","rates":{"EUR":1.5,"USD":1.25}}"""));

        ExchangeRates rates = source().latest();

        assertThat(rates.toEur(new BigDecimal("10"), Currency.getInstance("EUR")))
                .isEqualTo(Money.eur("10.00"));
        assertThat(rates.toEur(new BigDecimal("10"), USD)).isEqualTo(Money.eur("8.00"));
    }

    @Test
    @DisplayName("AC-10: a 5xx is retried; a success within the attempts returns the rates")
    void retriesServerErrors() {
        server.stubFor(get(urlPathEqualTo(PATH))
                .inScenario("fx")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("up"));
        server.stubFor(get(urlPathEqualTo(PATH))
                .inScenario("fx")
                .whenScenarioStateIs("up")
                .willReturn(okJson(BODY)));

        assertThat(source().latest().available()).isTrue();
        assertThat(requests()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-10: a 5xx on every attempt is unavailable after the configured attempts")
    void serverErrorOnEveryAttempt() {
        always(aResponse().withStatus(500));

        assertThatThrownBy(() -> source().latest())
                .isInstanceOf(ExchangeRatesUnavailableException.class)
                .hasMessageContaining("500");
        assertThat(requests()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-10: a 4xx is unavailable at once (our request was rejected; not retried)")
    void clientErrorNotRetried() {
        always(aResponse().withStatus(404));

        assertThatThrownBy(() -> source().latest()).isInstanceOf(ExchangeRatesUnavailableException.class);
        assertThat(requests()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-10: a response slower than the read timeout is unavailable")
    void timeout() {
        always(okJson(BODY).withFixedDelay(1_000));

        assertThatThrownBy(() -> source(Duration.ofMillis(100)).latest())
                .isInstanceOf(ExchangeRatesUnavailableException.class);
    }

    @Test
    @DisplayName("AC-10: a dropped connection is unavailable")
    void connectionReset() {
        always(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        assertThatThrownBy(() -> source().latest()).isInstanceOf(ExchangeRatesUnavailableException.class);
    }

    @Test
    @DisplayName("AC-10: an FX API that refuses connections is unavailable")
    void connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        FrankfurterExchangeRateSource source = new FrankfurterExchangeRateSource(
                URI.create("http://localhost:" + closedPort),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                RETRY);

        assertThatThrownBy(source::latest).isInstanceOf(ExchangeRatesUnavailableException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "not json",
                "[]",
                "{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-11-09\",\"rates\":{\"EUR\":0.9}}",
                "{\"amount\":1.0,\"date\":\"2026-11-09\",\"rates\":{\"USD\":1.1}}",
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-11-09\"}",
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-11-09\",\"rates\":[]}",
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-11-09\",\"rates\":{\"USD\":0}}",
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-11-09\",\"rates\":{\"USD\":-1.1}}",
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-11-09\",\"rates\":{\"USD\":\"1.1\"}}",
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"yesterday\",\"rates\":{\"USD\":1.1}}",
                "{\"amount\":2.0,\"base\":\"EUR\",\"date\":\"2026-11-09\",\"rates\":{\"USD\":2.2}}",
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-11-09\",\"rates\":{\"USD\":1e100000000}}",
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-11-09\",\"rates\":{\"USD\":1e-100000000}}"
            })
    @DisplayName("AC-10: a body that is not valid Frankfurter JSON with base EUR and positive, bounded rates is "
            + "unavailable, without retrying")
    void invalidBody(String body) {
        always(okJson(body));

        assertThatThrownBy(() -> source().latest()).isInstanceOf(ExchangeRatesUnavailableException.class);
        assertThat(requests()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-10: a server dribbling the body under the read timeout is cut by the attempt deadline, so a run "
            + "is delayed by seconds, never blocked")
    void dribblingBodyHitsTheAttemptDeadline() {
        always(okJson(BODY).withChunkedDribbleDelay(BODY.length(), 20_000));
        FrankfurterExchangeRateSource source = new FrankfurterExchangeRateSource(
                URI.create(server.baseUrl() + "/v1"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMillis(500),
                RETRY);
        long start = System.nanoTime();

        assertThatThrownBy(source::latest)
                .isInstanceOf(ExchangeRatesUnavailableException.class)
                .hasMessageContaining("no complete answer");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
        assertThat(requests()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-10: a body larger than 64 KiB is rejected without being read whole, and not retried")
    void hugeBodyRejected() {
        always(okJson("{\"amount\":1.0,\"pad\":\"" + "x".repeat(FrankfurterExchangeRateSource.MAX_BODY_BYTES) + "\"}"));

        assertThatThrownBy(() -> source().latest())
                .isInstanceOf(ExchangeRatesUnavailableException.class)
                .hasMessageContaining("larger than");
        assertThat(requests()).isEqualTo(1);
    }
}
