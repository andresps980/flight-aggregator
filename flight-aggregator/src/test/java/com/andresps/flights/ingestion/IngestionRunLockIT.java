package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.RecordedGdsMock.DAYS;
import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.andresps.flights.ingestion.RecordedGdsMock.feed;
import static com.andresps.flights.ingestion.RecordedGdsMock.ok;
import static com.andresps.flights.ingestion.RecordedGdsMock.request;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.ingestion.application.port.in.IngestionAlreadyRunningException;
import com.andresps.flights.ingestion.application.port.in.StartIngestionUseCase;
import com.andresps.flights.ingestion.application.port.in.StartedIngestion;
import com.andresps.flights.ingestion.application.port.out.RunLease;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import com.andresps.flights.ingestion.domain.RunStatus;
import com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.PostgresAdvisoryRunLock;
import com.andresps.flights.support.StoredData;
import com.andresps.flights.support.TestPostgres;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Spec 004 AC-17/AC-18 with the real advisory lock: concurrent triggers in this instance, and a lock held by another
 * "instance" (a second lock on the same database).
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
class IngestionRunLockIT {

    private static final WireMockServer SERVER = startServer();

    private static WireMockServer startServer() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        PROVIDERS.forEach(provider -> server.stubFor(request(provider, DAYS.getFirst())
                .willReturn(ok(provider, feed(provider, DAYS.getFirst())).withFixedDelay(500))));
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
    private StartIngestionUseCase ingestions;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyTables() {
        TestPostgres.truncateAll(dataSource);
    }

    @Test
    @DisplayName("AC-17: two triggers at the same moment start exactly one run")
    void concurrentTriggers() throws Exception {
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<StartedIngestion> trigger = () -> {
            together.await();
            return ingestions.start(IngestionTrigger.MANUAL);
        };
        List<StartedIngestion> started = new ArrayList<>();
        List<Throwable> rejected = new ArrayList<>();

        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<StartedIngestion>> attempts = List.of(threads.submit(trigger), threads.submit(trigger));
            for (Future<StartedIngestion> attempt : attempts) {
                try {
                    started.add(attempt.get());
                } catch (ExecutionException e) {
                    rejected.add(e.getCause());
                }
            }
        }

        assertThat(started).hasSize(1);
        assertThat(rejected).singleElement().isInstanceOf(IngestionAlreadyRunningException.class);
        IngestionRun run =
                started.getFirst().completion().orTimeout(30, TimeUnit.SECONDS).join();
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(new StoredData(dataSource).runs()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-17/AC-18: while another instance holds the lock no run starts; once it releases it, one can")
    void lockHeldByAnotherInstance() {
        RunLease otherInstance =
                new PostgresAdvisoryRunLock(dataSource).tryAcquire().orElseThrow();
        try {
            assertThatThrownBy(() -> ingestions.start(IngestionTrigger.SCHEDULED))
                    .isInstanceOf(IngestionAlreadyRunningException.class);
            assertThat(new StoredData(dataSource).runs()).isZero();
        } finally {
            otherInstance.close();
        }

        IngestionRun run = ingestions
                .start(IngestionTrigger.SCHEDULED)
                .completion()
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("AC-18: the lock is released when a run finishes, so the next run starts")
    void lockReleasedAfterRun() {
        UUID first = ingestions
                .start(IngestionTrigger.MANUAL)
                .completion()
                .orTimeout(30, TimeUnit.SECONDS)
                .join()
                .id();

        IngestionRun second = ingestions
                .start(IngestionTrigger.MANUAL)
                .completion()
                .orTimeout(30, TimeUnit.SECONDS)
                .join();

        assertThat(second.id()).isNotEqualTo(first);
        assertThat(new StoredData(dataSource).runStatuses()).containsExactly("COMPLETED", "COMPLETED");
    }
}
