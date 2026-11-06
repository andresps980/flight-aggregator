package com.andresps.flights.support;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole test JVM, with the image docker-compose uses ({@value #IMAGE}), so the build
 * starts PostgreSQL once.
 *
 * <ul>
 *   <li>Spring ITs {@code @Import(TestPostgres.class)}: the container becomes the {@code @ServiceConnection} of every
 *       context; they all share the same container instance, which no closing context stops.
 *   <li>Adapter ITs build adapters directly on {@link #dataSource()}, migrated by Flyway exactly as at startup.
 * </ul>
 *
 * Tests share one database, so each test class empties the tables it uses ({@link #truncateAll(DataSource)}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestPostgres {

    public static final String IMAGE = "postgres:17";

    private static final PostgreSQLContainer CONTAINER = startContainer();
    private static HikariDataSource dataSource;

    private static PostgreSQLContainer startContainer() {
        PostgreSQLContainer container = new SharedContainer().withDatabaseName("flights");
        container.start();
        return container;
    }

    /**
     * Never stopped by a closing Spring context (a {@code @DirtiesContext} test, or the context cache evicting one):
     * the other contexts and the adapter ITs still use it. Ryuk removes it when the JVM exits.
     */
    private static final class SharedContainer extends PostgreSQLContainer {

        SharedContainer() {
            super(IMAGE);
        }

        @Override
        public void stop() {
            // shared for the whole JVM: see the class comment
        }
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return CONTAINER;
    }

    /** A pooled data source on the shared database, with the Flyway migrations applied. */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(CONTAINER.getJdbcUrl());
            pool.setUsername(CONTAINER.getUsername());
            pool.setPassword(CONTAINER.getPassword());
            pool.setMaximumPoolSize(8);
            // Lets the batched-INSERT loader be measured at its best (ADR-0018); no effect on COPY.
            pool.addDataSourceProperty("reWriteBatchedInserts", "true");
            Flyway.configure().dataSource(pool).load().migrate();
            dataSource = pool;
        }
        return dataSource;
    }

    public static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }

    public static String username() {
        return CONTAINER.getUsername();
    }

    public static String password() {
        return CONTAINER.getPassword();
    }

    /** Empties every table of spec 004. */
    public static void truncateAll(DataSource dataSource) {
        JdbcClient.create(dataSource)
                .sql("TRUNCATE itinerary, day_snapshot, ingestion_run RESTART IDENTITY")
                .update();
    }
}
