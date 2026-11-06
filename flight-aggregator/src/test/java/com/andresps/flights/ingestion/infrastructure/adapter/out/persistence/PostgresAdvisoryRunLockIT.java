package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.ingestion.application.port.out.RunLease;
import com.andresps.flights.support.TestPostgres;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec 004 / ADR-0019: the session advisory lock, with two lock instances standing for two application instances on
 * the same database.
 */
class PostgresAdvisoryRunLockIT {

    private final DataSource dataSource = TestPostgres.dataSource();
    private final PostgresAdvisoryRunLock instanceA = nonPooled();
    private final PostgresAdvisoryRunLock instanceB = nonPooled();

    /** As the application builds it: every lease on its own physical connection. */
    private static PostgresAdvisoryRunLock nonPooled() {
        return PostgresAdvisoryRunLock.connectingTo(
                TestPostgres.jdbcUrl(), TestPostgres.username(), TestPostgres.password());
    }

    private final List<RunLease> leases = new ArrayList<>();

    @AfterEach
    void releaseAll() {
        leases.forEach(RunLease::close);
    }

    private Optional<RunLease> acquire(PostgresAdvisoryRunLock lock) {
        Optional<RunLease> lease = lock.tryAcquire();
        lease.ifPresent(leases::add);
        return lease;
    }

    @Test
    @DisplayName("spec 004 AC-17: while one instance holds the lock, another cannot take it; after release it can")
    void exclusiveAcrossInstances() {
        RunLease lease = acquire(instanceA).orElseThrow();

        assertThat(acquire(instanceB)).isEmpty();
        assertThat(acquire(instanceA)).as("not re-entrant either").isEmpty();
        assertThat(lease.isHeld()).isTrue();

        lease.close();

        assertThat(lease.isHeld()).isFalse();
        assertThat(acquire(instanceB)).isPresent();
    }

    @Test
    @DisplayName("spec 004 AC-17: two instances trying at the same moment: exactly one gets the lock")
    void concurrentAttempts() throws Exception {
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<Optional<RunLease>> attemptA = () -> {
            together.await();
            return instanceA.tryAcquire();
        };
        Callable<Optional<RunLease>> attemptB = () -> {
            together.await();
            return instanceB.tryAcquire();
        };
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<RunLease>> a = threads.submit(attemptA);
            Future<Optional<RunLease>> b = threads.submit(attemptB);
            a.get().ifPresent(leases::add);
            b.get().ifPresent(leases::add);
        }

        assertThat(leases).hasSize(1);
    }

    @Test
    @DisplayName(
            "spec 004 AC-18/AC-19: when the lock's session dies, the lock is released and the lease reports it lost")
    void sessionDeathReleasesTheLock() {
        RunLease lease = acquire(instanceA).orElseThrow();
        int lockHolder = JdbcClient.create(dataSource)
                .sql("SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND granted")
                .query(Integer.class)
                .single();

        JdbcClient.create(dataSource)
                .sql("SELECT pg_terminate_backend(?)")
                .param(lockHolder)
                .query(Boolean.class)
                .single();

        assertThat(lease.isHeld()).isFalse();
        assertThat(acquire(instanceB)).isPresent();
    }

    @Test
    @DisplayName("spec 004 AC-19: many day coordinators checking the lease at once all get a true answer")
    void concurrentChecks() throws Exception {
        RunLease lease = acquire(instanceA).orElseThrow();
        List<Future<Boolean>> checks = new ArrayList<>();

        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                checks.add(threads.submit(lease::isHeld));
            }
        }

        for (Future<Boolean> check : checks) {
            assertThat(check.get()).isTrue();
        }
    }

    @Test
    @DisplayName("spec 004 AC-18: the lock is held on its own connection, closed (not pooled) when released")
    void dedicatedConnection() {
        acquire(instanceA).orElseThrow();

        long sessionsHoldingIt = JdbcClient.create(dataSource)
                .sql(
                        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted AND pid <> pg_backend_pid()")
                .query(Long.class)
                .single();

        assertThat(sessionsHoldingIt).isEqualTo(1);
        leases.getFirst().close();
        assertThat(JdbcClient.create(dataSource)
                        .sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
