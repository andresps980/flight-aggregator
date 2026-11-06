package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.ingestion.application.port.out.IngestionRunRepository;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import com.andresps.flights.ingestion.domain.RunStatus;
import com.andresps.flights.ingestion.infrastructure.json.IngestionReportJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Ingestion runs in {@code ingestion_run}, the report as JSONB (spec 004, AC-14). */
public final class PostgresIngestionRunRepository implements IngestionRunRepository {

    private static final String COLUMNS = "id, trigger, status, started_at, finished_at, report, failure";

    private final JdbcClient jdbc;

    public PostgresIngestionRunRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void create(IngestionRun run) {
        jdbc.sql("INSERT INTO ingestion_run (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)")
                .param(run.id())
                .param(run.trigger().name())
                .param(run.status().name())
                .param(utc(run.startedAt()))
                .param(utc(run.finishedAt()))
                .param(report(run))
                .param(run.failure())
                .update();
    }

    @Override
    public void update(IngestionRun run) {
        int updated = jdbc.sql(
                        "UPDATE ingestion_run SET status = ?, finished_at = ?, report = ?::jsonb, failure = ? WHERE id = ?")
                .param(run.status().name())
                .param(utc(run.finishedAt()))
                .param(report(run))
                .param(run.failure())
                .param(run.id())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("No ingestion run " + run.id() + " to update");
        }
    }

    @Override
    public Optional<IngestionRun> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ingestion_run WHERE id = ?")
                .param(id)
                .query(PostgresIngestionRunRepository::run)
                .optional();
    }

    @Override
    public Optional<IngestionRun> findLatest() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ingestion_run ORDER BY started_at DESC, id LIMIT 1")
                .query(PostgresIngestionRunRepository::run)
                .optional();
    }

    @Override
    public Optional<UUID> findRunning() {
        return jdbc.sql("SELECT id FROM ingestion_run WHERE status = 'RUNNING' ORDER BY started_at DESC LIMIT 1")
                .query(UUID.class)
                .optional();
    }

    @Override
    public int abortRunning(Instant at) {
        return jdbc.sql("""
                        UPDATE ingestion_run SET status = 'ABORTED', finished_at = ?, failure = ?
                        WHERE status = 'RUNNING'""").param(utc(at)).param(IngestionRun.ABORTED_FAILURE).update();
    }

    @Override
    public int deleteStartedBefore(Instant instant) {
        return jdbc.sql("DELETE FROM ingestion_run WHERE started_at < ?")
                .param(utc(instant))
                .update();
    }

    private static String report(IngestionRun run) {
        return run.report() == null ? null : IngestionReportJson.write(run.report());
    }

    private static IngestionRun run(ResultSet rs, int row) throws SQLException {
        String report = rs.getString("report");
        return new IngestionRun(
                rs.getObject("id", UUID.class),
                IngestionTrigger.valueOf(rs.getString("trigger")),
                RunStatus.valueOf(rs.getString("status")),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(rs.getObject("finished_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant)
                        .orElse(null),
                report == null ? null : IngestionReportJson.read(report),
                rs.getString("failure"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
