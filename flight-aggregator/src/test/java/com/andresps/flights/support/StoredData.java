package com.andresps.flights.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads spec 004's tables with plain SQL, independently of the adapters under test. */
public final class StoredData {

    private final JdbcClient jdbc;

    public StoredData(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Days that have itinerary rows, in order. */
    public List<LocalDate> days() {
        return jdbc.sql("SELECT DISTINCT departure_date FROM itinerary ORDER BY departure_date")
                .query(LocalDate.class)
                .list();
    }

    public long rows(LocalDate day) {
        return jdbc.sql("SELECT count(*) FROM itinerary WHERE departure_date = ?")
                .param(day)
                .query(Long.class)
                .single();
    }

    public long rows() {
        return jdbc.sql("SELECT count(*) FROM itinerary").query(Long.class).single();
    }

    /** Distinct run ids among a day's rows: a snapshot is always written by exactly one run. */
    public List<UUID> runIds(LocalDate day) {
        return jdbc.sql("SELECT DISTINCT run_id FROM itinerary WHERE departure_date = ?")
                .param(day)
                .query(UUID.class)
                .list();
    }

    public Optional<DayRow> snapshot(LocalDate day) {
        return jdbc.sql("""
                        SELECT departure_date, run_id, providers, missing_providers, complete, itineraries, stale_since,
                               last_run_id
                        FROM day_snapshot WHERE departure_date = ?""")
                .param(day)
                .query((rs, n) -> new DayRow(
                        rs.getObject("departure_date", LocalDate.class),
                        rs.getObject("run_id", UUID.class),
                        Arrays.asList((String[]) rs.getArray("providers").getArray()),
                        Arrays.asList(
                                (String[]) rs.getArray("missing_providers").getArray()),
                        rs.getBoolean("complete"),
                        rs.getInt("itineraries"),
                        Optional.ofNullable(rs.getObject("stale_since", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)
                                .orElse(null),
                        rs.getObject("last_run_id", UUID.class)))
                .optional();
    }

    public long runs() {
        return jdbc.sql("SELECT count(*) FROM ingestion_run").query(Long.class).single();
    }

    public List<String> runStatuses() {
        return jdbc.sql("SELECT status FROM ingestion_run ORDER BY started_at")
                .query(String.class)
                .list();
    }

    /** When {@code itinerary} was last analyzed (manually), or empty. */
    public Optional<Instant> lastAnalyze() {
        return jdbc.sql("SELECT last_analyze FROM pg_stat_user_tables WHERE relname = 'itinerary'")
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant))
                .single();
    }

    public record DayRow(
            LocalDate day,
            UUID runId,
            List<String> providers,
            List<String> missingProviders,
            boolean complete,
            int itineraries,
            Instant staleSince,
            UUID lastRunId) {}
}
