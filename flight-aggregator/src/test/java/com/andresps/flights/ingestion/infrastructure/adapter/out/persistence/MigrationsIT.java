package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.ingestion.RecordedFeedsSpringTest;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec 004 AC-1: Flyway creates the schema when the application starts (the shared recorded-feeds context), and a
 * migrated database gets nothing more.
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
    @DisplayName("spec 004 AC-1: an already migrated database applies nothing")
    void migratedDatabaseAppliesNothing() {
        int applied = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;

        assertThat(applied).isZero();
    }
}
