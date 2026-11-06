package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.complete;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itineraries;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itinerary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.support.TestPostgres;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec 005 / ADR-0022: every transaction that changes {@code itinerary} or {@code day_snapshot} increments the data
 * version, in the same transaction, so the search cache never serves data older than PostgreSQL's.
 */
class DataVersionIT {

    private static final LocalDate DAY = LocalDate.parse("2026-11-07");

    private final DataSource dataSource = TestPostgres.dataSource();
    private final PostgresDaySnapshotStore store = PostgresDaySnapshotStore.withCopy(dataSource);

    @BeforeEach
    void emptyTables() {
        TestPostgres.truncateAll(dataSource);
    }

    private long version() {
        return JdbcClient.create(dataSource)
                .sql("SELECT version FROM data_version")
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("ADR-0022: the data version is a single row")
    void singleRow() {
        assertThat(JdbcClient.create(dataSource)
                        .sql("SELECT count(*) FROM data_version")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("AC-19: replacing a day increments the data version")
    void replaceBumps() {
        long before = version();

        store.replace(complete(DAY, UUID.randomUUID(), itineraries(DAY, 3)));

        assertThat(version()).isGreaterThan(before);
    }

    @Test
    @DisplayName("AC-19: marking a day stale increments the data version")
    void markStaleBumps() {
        store.replace(complete(DAY, UUID.randomUUID(), itineraries(DAY, 1)));
        long before = version();

        store.markStale(DAY, UUID.randomUUID(), Instant.parse("2026-11-06T12:00:00Z"));

        assertThat(version()).isGreaterThan(before);
    }

    @Test
    @DisplayName("AC-19: retention increments the data version")
    void retentionBumps() {
        store.replace(complete(DAY, UUID.randomUUID(), itineraries(DAY, 1)));
        long before = version();

        store.deleteDaysBefore(DAY.plusDays(1));

        assertThat(version()).isGreaterThan(before);
    }

    @Test
    @DisplayName("ADR-0022: a failed day write rolls the version back with the data")
    void failedWriteDoesNotBump() {
        store.replace(complete(DAY, UUID.randomUUID(), itineraries(DAY, 1)));
        long before = version();

        // the same itinerary twice violates the unique key: the whole day rolls back
        assertThatThrownBy(() ->
                        store.replace(complete(DAY, UUID.randomUUID(), List.of(itinerary(DAY, 7), itinerary(DAY, 7)))))
                .isInstanceOf(RuntimeException.class);

        assertThat(version()).isEqualTo(before);
    }
}
