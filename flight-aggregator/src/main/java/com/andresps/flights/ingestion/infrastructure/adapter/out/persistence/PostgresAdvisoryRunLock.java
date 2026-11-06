package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.ingestion.application.port.out.IngestionRunLock;
import com.andresps.flights.ingestion.application.port.out.RunLease;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * The run lock as a PostgreSQL session-level advisory lock (ADR-0019). A session lock belongs to its connection, so
 * the lease keeps that connection for the whole run and closes it when released: handing it back to the pool earlier
 * would release the lock or leak it to another borrower. If the session dies (network, database restart, terminated
 * backend), PostgreSQL releases the lock and {@link RunLease#isHeld()} turns false.
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

    private static final class Lease implements RunLease {

        private final Connection connection;
        private final AtomicBoolean closed = new AtomicBoolean();

        Lease(Connection connection) {
            this.connection = connection;
        }

        /** Asks this session whether it still holds the lock; any failure means the session is gone. */
        @Override
        public boolean isHeld() {
            if (closed.get()) {
                return false;
            }
            try {
                return queryBoolean(
                        connection,
                        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND granted"
                                + " AND pid = pg_backend_pid() AND classid = 0 AND objsubid = 1 AND objid = ?::oid)");
            } catch (SQLException e) {
                LOG.warn("The ingestion lock's session is gone: {}", e.getMessage());
                return false;
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                queryBoolean(connection, "SELECT pg_advisory_unlock(?)");
            } catch (SQLException e) {
                // A dead session has already released the lock.
                LOG.debug("Unlocking failed; the session is gone", e);
            } finally {
                closeQuietly(connection);
            }
        }
    }
}
