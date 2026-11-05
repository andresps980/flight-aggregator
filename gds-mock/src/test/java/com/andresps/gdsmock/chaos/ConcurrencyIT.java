package com.andresps.gdsmock.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.support.Http;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/** Fixed latencies of 300, 600 and 900 ms: parallel requests must not wait for each other. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gds.chaos.providers.mock-alpha.latency-min=300ms",
            "gds.chaos.providers.mock-alpha.latency-max=300ms",
            "gds.chaos.providers.mock-beta.latency-min=600ms",
            "gds.chaos.providers.mock-beta.latency-max=600ms",
            "gds.chaos.providers.mock-gamma.latency-min=900ms",
            "gds.chaos.providers.mock-gamma.latency-max=900ms"
        })
@ActiveProfiles({"deterministic", "it"})
class ConcurrencyIT {

    @Value("${local.server.port}")
    private int port;

    @Test
    @DisplayName("AC-15: the three providers requested in parallel answer in about the slowest latency, not the sum")
    void parallelRequestsDoNotQueue() {
        Http http = new Http(port);
        http.get("/mock-alpha/flights?date=2026-11-06"); // warm-up: first request initialises the servlet stack

        long start = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<HttpStatus>> calls = List.of("mock-alpha", "mock-beta", "mock-gamma").stream()
                    .map(provider -> CompletableFuture.supplyAsync(
                            () -> HttpStatus.valueOf(http.get("/" + provider + "/flights?date=2026-11-06")
                                    .getStatusCode()
                                    .value()),
                            executor))
                    .toList();
            assertThat(calls).allSatisfy(call -> assertThat(call.join()).isEqualTo(HttpStatus.OK));
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(900)).isLessThan(Duration.ofMillis(1_500));
    }
}
