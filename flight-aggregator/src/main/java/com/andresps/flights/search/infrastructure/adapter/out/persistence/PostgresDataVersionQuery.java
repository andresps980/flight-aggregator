package com.andresps.flights.search.infrastructure.adapter.out.persistence;

import com.andresps.flights.search.application.port.out.DataVersionQuery;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the single-row {@code data_version} table that ingestion bumps with every change (ADR-0022). */
public final class PostgresDataVersionQuery implements DataVersionQuery {

    private final JdbcClient jdbc;

    public PostgresDataVersionQuery(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public long current() {
        return jdbc.sql("SELECT version FROM data_version").query(Long.class).single();
    }
}
