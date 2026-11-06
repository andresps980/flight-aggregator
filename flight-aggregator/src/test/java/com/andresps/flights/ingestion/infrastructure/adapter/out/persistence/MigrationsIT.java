package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.ingestion.RecordedFeedsSpringTest;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec 004 AC-1 (and spec 005's additions): Flyway creates the schema when the application starts (the shared
 * recorded-feeds context), and a migrated database gets nothing more.
 */
class MigrationsIT extends RecordedFeedsSpringTest {

    private List<String> columns(String table) {
        return JdbcClient.create(dataSource)
                .sql(
                        "SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position")
                .param(table)
                .query(String.class)
                .list();
    }

    @Test
    @DisplayName("spec 004 AC-1: the application starts with the three tables and their columns")
    void schemaExists() {
        assertThat(columns("itinerary"))
                .containsExactly(
                        "id",
                        "itinerary_key",
                        "departure_date",
                        "origin",
                        "destination",
                        "departure_at",
                        "arrival_at",
                        "airline",
                        "stops",
                        "base_price",
                        "final_price",
                        "selected_provider",
                        "providers",
                        "segments",
                        "run_id",
                        "created_at");
        assertThat(columns("day_snapshot"))
                .containsExactly(
                        "departure_date",
                        "run_id",
                        "published_at",
                        "providers",
                        "missing_providers",
                        "complete",
                        "itineraries",
                        "stale_since",
                        "last_run_id");
        assertThat(columns("ingestion_run"))
                .containsExactly("id", "trigger", "status", "started_at", "finished_at", "report", "failure");
    }

    @Test
    @DisplayName("spec 004 AC-1: keys, types and indexes the writes and the search rely on")
    void constraintsAndIndexes() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        List<String> indexes = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'itinerary'")
                .query(String.class)
                .list();
        assertThat(indexes)
                .anySatisfy(def -> assertThat(def).contains("UNIQUE").contains("(id)"))
                .anySatisfy(def -> assertThat(def).contains("UNIQUE").contains("(itinerary_key)"))
                .anySatisfy(def -> assertThat(def).contains("(departure_date)"));
        assertThat(jdbc.sql("SELECT data_type FROM information_schema.columns WHERE table_name = 'itinerary'"
                                + " AND column_name IN ('base_price', 'final_price', 'segments', 'providers', 'id')"
                                + " ORDER BY column_name")
                        .query(String.class)
                        .list())
                .containsExactly("numeric", "numeric", "bigint", "ARRAY", "jsonb");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'ingestion_run'")
                        .query(String.class)
                        .list())
                .anySatisfy(def -> assertThat(def).contains("(started_at"));
    }

    @Test
    @DisplayName("spec 005 / ADR-0022: data_version exists with exactly one row, and a second row is refused")
    void dataVersionTable() {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        assertThat(columns("data_version")).containsExactly("id", "version");
        assertThat(jdbc.sql("SELECT count(*) FROM data_version")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO data_version (id, version) VALUES (false, 0)")
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("spec 005 AC-22 / ADR-0020: the search indexes serve the keyset order (final_price, id)")
    void searchIndexes() {
        List<String> indexes = JdbcClient.create(dataSource)
                .sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'itinerary'")
                .query(String.class)
                .list();

        assertThat(indexes)
                .anySatisfy(def -> assertThat(def).endsWith("(final_price, id)"))
                .anySatisfy(def -> assertThat(def).endsWith("(origin, destination, final_price, id)"));
    }

    @Test
    @DisplayName("spec 004 AC-1: an already migrated database applies nothing")
    void migratedDatabaseAppliesNothing() {
        int applied = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;

        assertThat(applied).isZero();
    }
}
