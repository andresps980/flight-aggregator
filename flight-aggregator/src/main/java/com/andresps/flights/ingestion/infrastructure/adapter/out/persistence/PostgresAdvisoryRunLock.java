package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.ingestion.application.port.out.IngestionRunLock;
import com.andresps.flights.ingestion.application.port.out.RunLease;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The run lock as a PostgreSQL session-level advisory lock (ADR-0019). A session lock belongs to its connection, so
 * the lease keeps that connection for the whole run and closes it when released. In the application the connection
 * comes from a <em>non-pooled</em> data source ({@link #connectingTo}): closing it always ends the session, so the lock
 * can never go back to a pool still held (for instance if the unlock times out), and it does not use a pool slot. If
 * the session dies (network, database restart, terminated backend), PostgreSQL releases the lock and
 * {@link RunLease#isHeld()} turns false.
 */
public final class PostgresAdvisoryRunLock implements IngestionRunLock {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresAdvisoryRunLock.class);

    /** Application-wide key of the ingestion lock ("spec 004, ADR-0019"); below 2^31, so pg_locks shows it as objid. */
    static final long KEY = 4_004_019L;

    private static final int CHECK_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;

    public PostgresAdvisoryRunLock(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** A lock whose every lease opens its own physical connection (no pool). */
    public static PostgresAdvisoryRunLock connectingTo(String url, String username, String password) {
        return new PostgresAdvisoryRunLock(new DriverManagerDataSource(url, username, password));
    }

    @Override
    public Optional<RunLease> tryAcquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            if (queryBoolean(connection, "SELECT pg_try_advisory_lock(?)")) {
                return Optional.of(new Lease(connection));
            }
            connection.close();
            return Optional.empty();
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new UncategorizedSQLException("Acquiring the ingestion lock", null, e);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(CHECK_TIMEOUT_SECONDS);
            statement.setLong(1, KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.debug("Closing the lock connection failed", e);
            }
        }
    }

    /**
     * Several day coordinators ask {@link #isHeld()} concurrently, and a JDBC connection is not thread-safe: every use
     * of the connection is serialised (a {@link ReentrantLock}, which does not pin virtual threads).
     */
    private static final class Lease implements RunLease {

        private final Connection connection;
        private final ReentrantLock use = new ReentrantLock();
        private boolean closed;

        Lease(Connection connection) {
            this.connection = connection;
        }

        /** Asks this session whether it still holds the lock; any failure means it cannot be trusted to. */
        @Override
        public boolean isHeld() {
            use.lock();
            try {
                return !closed
                        && queryBoolean(
                                connection,
                                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND granted AND pid ="
                                        + " pg_backend_pid() AND classid = 0 AND objsubid = 1 AND objid = ?::oid)");
            } catch (SQLException | RuntimeException e) {
                LOG.warn("The ingestion lock cannot be confirmed, treating it as lost: {}", e.toString());
                return false;
            } finally {
                use.unlock();
            }
        }

        /** Unlocks, then closes the connection; closing ends the session, which releases the lock in any case. */
        @Override
        public void close() {
            use.lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    queryBoolean(connection, "SELECT pg_advisory_unlock(?)");
                } catch (SQLException | RuntimeException e) {
                    LOG.debug("Unlocking failed; closing the session releases the lock", e);
                } finally {
                    closeQuietly(connection);
                }
            } finally {
                use.unlock();
            }
        }
    }
}
