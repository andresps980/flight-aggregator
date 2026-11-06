package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotStore;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotWrite;
import com.andresps.flights.ingestion.application.port.out.SnapshotMaintenance;
import com.andresps.flights.ingestion.domain.DaySnapshot;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Day snapshots in PostgreSQL (spec 004, ADR-0017, ADR-0018). A day is replaced in one transaction: delete its rows,
 * bulk load the new ones, upsert its {@code day_snapshot} row. MVCC gives concurrent readers the old or the new rows,
 * never a mix; on failure the transaction rolls back and the previous snapshot stays.
 */
public final class PostgresDaySnapshotStore implements DaySnapshotStore, SnapshotMaintenance {

    private final DataSource dataSource;
    private final ItineraryBulkLoader loader;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PostgresDaySnapshotStore(DataSource dataSource, ItineraryBulkLoader loader) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** The store used by the application: COPY with a 64 KiB buffer (ADR-0018). */
    public static PostgresDaySnapshotStore withCopy(DataSource dataSource) {
        return new PostgresDaySnapshotStore(dataSource, new CopyItineraryLoader(new ItineraryCopyEncoder(64 * 1024)));
    }

    @Override
    public Optional<DaySnapshot> find(LocalDate day) {
        return jdbc.sql(
                        "SELECT run_id, providers, published_at, stale_since FROM day_snapshot WHERE departure_date = ?")
                .param(day)
                .query((rs, n) -> new DaySnapshot(
                        day,
                        rs.getObject("run_id", UUID.class),
                        providers(rs.getArray("providers")),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(rs.getObject("stale_since", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)
                                .orElse(null)))
                .optional();
    }

    @Override
    public void replace(DaySnapshotWrite write) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM itinerary WHERE departure_date = ?")
                    .param(write.day())
                    .update();
            long rows = load(write);
            jdbc.sql("""
                            INSERT INTO day_snapshot (departure_date, run_id, published_at, providers, missing_providers,
                                                      complete, itineraries, stale_since, last_run_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)
                            ON CONFLICT (departure_date) DO UPDATE SET
                                run_id = excluded.run_id, published_at = excluded.published_at,
                                providers = excluded.providers, missing_providers = excluded.missing_providers,
                                complete = excluded.complete, itineraries = excluded.itineraries, stale_since = NULL,
                                last_run_id = excluded.last_run_id""")
                    .param(write.day())
                    .param(write.runId())
                    .param(utc(write.publishedAt()))
                    .param(ids(write.providers()))
                    .param(ids(write.missing()))
                    .param(write.complete())
                    .param(Math.toIntExact(rows))
                    .param(write.runId())
                    .update();
        });
    }

    /** Loads on the transaction's connection (bound by the template), so the load commits or rolls back with it. */
    private long load(DaySnapshotWrite write) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            return loader.load(connection, write);
        } catch (SQLException e) {
            throw new UncategorizedSQLException("Loading " + write.day(), null, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public void markStale(LocalDate day, UUID runId, Instant at) {
        jdbc.sql("""
                        UPDATE day_snapshot SET stale_since = COALESCE(stale_since, ?), last_run_id = ?
                        WHERE departure_date = ?""").param(utc(at)).param(runId).param(day).update();
    }

    @Override
    public void deleteDaysBefore(LocalDate day) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM itinerary WHERE departure_date < ?")
                    .param(day)
                    .update();
            jdbc.sql("DELETE FROM day_snapshot WHERE departure_date < ?")
                    .param(day)
                    .update();
        });
    }

    @Override
    public void refreshStatistics() {
        jdbc.sql("ANALYZE itinerary").update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String[] ids(Collection<ProviderId> providers) {
        return providers.stream().map(ProviderId::value).toArray(String[]::new);
    }

    private static SortedSet<ProviderId> providers(Array array) throws SQLException {
        return new TreeSet<>(
                Arrays.stream((String[]) array.getArray()).map(ProviderId::new).toList());
    }
}
