package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.application.port.out.PartitionFetchException;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.domain.ProviderPartition;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.gamma.GammaCsvParser;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Resilience of one provider feed (ADR-0014) against a WireMock provider: timeouts, deadlines, retries, circuit breaker
 * and partition atomicity. No Spring context: the adapter is built as the composition root builds it. All timings are
 * scaled down to milliseconds; assertions on time use generous upper bounds only.
 */
class HttpProviderFeedIT {

    private static final LocalDate DAY = LocalDate.parse("2026-11-06");
    private static final String PATH = "/mock-gamma/flights";
    private static final String LINE =
            "GAM-%d,100.00,IB0345~MAD~2026-11-06T09:00~Europe/Madrid~BCN~2026-11-06T10:25~Europe/Madrid\n";
    private static final String HEADER = "REF,PRICE_EUR,SEGMENTS\n";
    private static final String BODY = HEADER + LINE.formatted(1) + LINE.formatted(2) + LINE.formatted(3);

    /** read 300 ms, attempt 1.5 s, 3 attempts with 10 ms backoff, breaker 10/10/80 % with 400 ms open wait. */
    static final ResilienceSettings SCALED = new ResilienceSettings(
            Duration.ofMillis(500),
            Duration.ofMillis(300),
            Duration.ofMillis(1500),
            new RetrySettings(3, Duration.ofMillis(10), 2.0, 0.5),
            new CircuitBreakerSettings(10, 10, 80f, Duration.ofMillis(400), 2));

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

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private HttpProviderFeed feed(ResilienceSettings settings) {
        return feed(URI.create(server.baseUrl()), settings);
    }

    private HttpProviderFeed feed(URI baseUrl, ResilienceSettings settings) {
        return new HttpProviderFeed(
                new GammaCsvParser(),
                baseUrl,
                settings,
                ResiliencePolicies.circuitBreaker("mock-gamma", settings.circuitBreaker()));
    }

    private static ResponseDefinitionBuilder csv(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/csv;charset=UTF-8")
                .withBody(body);
    }

    private static void always(ResponseDefinitionBuilder response) {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(response));
    }

    private static void firstThen(ResponseDefinitionBuilder first, ResponseDefinitionBuilder then) {
        server.stubFor(get(urlPathEqualTo(PATH))
                .inScenario("partition")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(first)
                .willSetStateTo("second"));
        server.stubFor(get(urlPathEqualTo(PATH))
                .inScenario("partition")
                .whenScenarioStateIs("second")
                .willReturn(then));
    }

    private static int requests() {
        return server.findAll(getRequestedFor(urlPathEqualTo(PATH))).size();
    }

    private static PartitionFetchException failure(HttpProviderFeed feed) {
        return catchThrowableOfType(PartitionFetchException.class, () -> feed.fetch(DAY));
    }

    @Test
    @DisplayName("AC-1/AC-9: requests {base}/{provider}/flights?date=day and returns the parsed partition")
    void fetchesPartition() {
        always(csv(
                BODY + "GAM-4,-1.00,IB0345~MAD~2026-11-06T09:00~Europe/Madrid~BCN~2026-11-06T10:25~Europe/Madrid\n"));

        ProviderPartition partition = feed(SCALED).fetch(DAY);

        assertThat(partition.provider()).isEqualTo(new ProviderId("mock-gamma"));
        assertThat(partition.day()).isEqualTo(DAY);
        assertThat(partition.offers()).hasSize(3);
        assertThat(partition.malformed()).containsEntry(MalformedReason.NON_POSITIVE_PRICE, 1L);
        assertThat(partition.attempts()).isEqualTo(1);
        server.verify(
                1,
                getRequestedFor(urlPathEqualTo(PATH))
                        .withQueryParam("date", com.github.tomakehurst.wiremock.client.WireMock.equalTo("2026-11-06")));
    }

    @Test
    @DisplayName("AC-12: a first byte slower than read-timeout times out and the retry succeeds (attempts = 2)")
    void slowFirstByteThenSuccess() {
        firstThen(csv(BODY).withFixedDelay(800), csv(BODY));

        ProviderPartition partition = feed(SCALED).fetch(DAY);

        assertThat(partition.attempts()).isEqualTo(2);
        assertThat(partition.offers()).hasSize(3);
    }

    @Test
    @DisplayName("AC-12: a body that stalls longer than read-timeout times out and the retry succeeds")
    void stalledBodyThenSuccess() {
        firstThen(csv(BODY).withChunkedDribbleDelay(2, 1200), csv(BODY));

        ProviderPartition partition = feed(SCALED).fetch(DAY);

        assertThat(partition.attempts()).isEqualTo(2);
        assertThat(partition.offers()).hasSize(3);
    }

    @Test
    @DisplayName("AC-13: a body trickling past attempt-timeout (never stalling) is aborted as TIMEOUT on every attempt")
    void tricklingBodyHitsAttemptDeadline() {
        ResilienceSettings settings = new ResilienceSettings(
                SCALED.connectTimeout(),
                Duration.ofMillis(400),
                Duration.ofMillis(800),
                new RetrySettings(2, Duration.ofMillis(10), 2.0, 0.5),
                SCALED.circuitBreaker());
        always(csv(BODY).withChunkedDribbleDelay(40, 4000));
        long start = System.nanoTime();

        PartitionFetchException failure = failure(feed(settings));

        assertThat(failure.kind()).isEqualTo(FailureKind.TIMEOUT);
        assertThat(failure.attempts()).isEqualTo(2);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
    }

    @Test
    @DisplayName("AC-13: a trickling first attempt is aborted and the retry succeeds")
    void tricklingThenSuccess() {
        firstThen(csv(BODY).withChunkedDribbleDelay(40, 4000), csv(BODY));

        ProviderPartition partition = feed(SCALED).fetch(DAY);

        assertThat(partition.attempts()).isEqualTo(2);
        assertThat(partition.offers()).hasSize(3);
    }

    @Test
    @DisplayName("AC-14: a 500 is retried and the second attempt succeeds")
    void serverErrorThenSuccess() {
        firstThen(aResponse().withStatus(500), csv(BODY));

        ProviderPartition partition = feed(SCALED).fetch(DAY);

        assertThat(partition.attempts()).isEqualTo(2);
        assertThat(requests()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-14: a connection reset is an I/O error, retried up to max-attempts")
    void connectionReset() {
        // HttpURLConnection itself re-sends a GET once after a reset, so a single reset never reaches our retry
        // (SOLUTION.md); a persistent one does.
        always(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        PartitionFetchException failure = failure(feed(SCALED));

        assertThat(failure.kind()).isEqualTo(FailureKind.IO_ERROR);
        assertThat(failure.attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-14: a refused connection fails as IO_ERROR after max-attempts")
    void connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        PartitionFetchException failure = failure(feed(URI.create("http://localhost:" + closedPort), SCALED));

        assertThat(failure.kind()).isEqualTo(FailureKind.IO_ERROR);
        assertThat(failure.attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "AC-14/AC-18: a truncated attempt is retried and its offers are not kept: the retry's are counted once")
    void truncatedAttemptLeavesNothingBehind() {
        String truncated = HEADER + LINE.formatted(1) + LINE.formatted(2) + "GAM-3,100.00,IB0345~MAD~2026";
        firstThen(csv(truncated), csv(BODY));

        ProviderPartition partition = feed(SCALED).fetch(DAY);

        assertThat(partition.attempts()).isEqualTo(2);
        assertThat(partition.offers()).hasSize(3);
        assertThat(partition.recordsReceived()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-7/AC-14: a document broken on every attempt fails as MALFORMED_DOCUMENT after max-attempts")
    void persistentMalformedDocument() {
        always(csv("not,a,gamma,feed\n"));

        PartitionFetchException failure = failure(feed(SCALED));

        assertThat(failure.kind()).isEqualTo(FailureKind.MALFORMED_DOCUMENT);
        assertThat(failure.attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-14: a 4xx fails at once as CLIENT_ERROR, without retries")
    void clientErrorIsNotRetried() {
        always(aResponse().withStatus(400));

        PartitionFetchException failure = failure(feed(SCALED));

        assertThat(failure.kind()).isEqualTo(FailureKind.CLIENT_ERROR);
        assertThat(failure.attempts()).isEqualTo(1);
        assertThat(requests()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-15: a partition answering 500 on every attempt fails as SERVER_ERROR after max-attempts")
    void persistentServerError() {
        always(aResponse().withStatus(500));

        PartitionFetchException failure = failure(feed(SCALED));

        assertThat(failure.kind()).isEqualTo(FailureKind.SERVER_ERROR);
        assertThat(failure.attempts()).isEqualTo(3);
        assertThat(requests()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-16: a 503 outage opens the breaker after minimum-calls attempts; later fetches make no request")
    void outageOpensBreaker() {
        always(aResponse().withStatus(503));
        HttpProviderFeed feed = feed(SCALED);

        for (int i = 0; i < 4; i++) {
            failure(feed);
        }
        int requestsWhenOpened = requests();
        PartitionFetchException shortCircuited = failure(feed);

        assertThat(requestsWhenOpened).isEqualTo(10);
        assertThat(feed.circuitState()).isEqualTo(CircuitState.OPEN);
        assertThat(shortCircuited.kind()).isEqualTo(FailureKind.CIRCUIT_OPEN);
        assertThat(shortCircuited.attempts()).isZero();
        assertThat(requests()).isEqualTo(requestsWhenOpened);
    }

    @Test
    @DisplayName("AC-16: after open-wait, half-open probes that succeed close the breaker again")
    void recoversThroughHalfOpen() throws InterruptedException {
        always(aResponse().withStatus(503));
        HttpProviderFeed feed = feed(SCALED);
        for (int i = 0; i < 4; i++) {
            failure(feed);
        }
        assertThat(feed.circuitState()).isEqualTo(CircuitState.OPEN);

        server.resetAll();
        always(csv(BODY));
        Thread.sleep(SCALED.circuitBreaker().openWait().plusMillis(200).toMillis());

        feed.fetch(DAY);
        assertThat(feed.circuitState()).isEqualTo(CircuitState.HALF_OPEN);
        feed.fetch(DAY);
        assertThat(feed.circuitState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("AC-16: 4xx responses never open the breaker")
    void clientErrorsDoNotOpenBreaker() {
        always(aResponse().withStatus(404));
        HttpProviderFeed feed = feed(SCALED);

        for (int i = 0; i < 12; i++) {
            assertThat(failure(feed).kind()).isEqualTo(FailureKind.CLIENT_ERROR);
        }

        assertThat(feed.circuitState()).isEqualTo(CircuitState.CLOSED);
        assertThat(requests()).isEqualTo(12);
    }

    @Test
    @DisplayName("AC-13: an interrupted fetch (run deadline) stops at once: the request is aborted and not retried")
    void interruptionAbortsFetch() throws InterruptedException {
        ResilienceSettings patient = new ResilienceSettings(
                SCALED.connectTimeout(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                SCALED.retry(),
                SCALED.circuitBreaker());
        always(csv(BODY).withFixedDelay(5000));
        HttpProviderFeed feed = feed(patient);
        AtomicReference<Throwable> outcome = new AtomicReference<>();

        Thread fetching = Thread.ofVirtual().start(() -> {
            try {
                feed.fetch(DAY);
            } catch (RuntimeException e) {
                outcome.set(e);
            }
        });
        Thread.sleep(300);
        long interruptedAt = System.nanoTime();
        fetching.interrupt();
        fetching.join(Duration.ofSeconds(2));

        assertThat(fetching.isAlive()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - interruptedAt)).isLessThan(Duration.ofSeconds(1));
        assertThat(outcome.get()).isInstanceOf(PartitionFetchException.class);
        assertThat(requests()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-23: a feed is named after its parser's provider")
    void providerComesFromParser() {
        assertThat(feed(SCALED).provider()).isEqualTo(new ProviderId("mock-gamma"));
        assertThatThrownBy(() -> new HttpProviderFeed(new GammaCsvParser(), null, SCALED, null))
                .isInstanceOf(NullPointerException.class);
    }
}
