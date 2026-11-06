package com.andresps.flights.search.infrastructure.adapter.out.persistence;

import com.andresps.flights.search.application.port.out.ItinerarySearchQuery;
import com.andresps.flights.search.domain.ItineraryView;
import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.SearchCriteria;
import com.andresps.flights.search.domain.SearchSlice;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One keyset page from PostgreSQL: {@code limit + 1} rows are read, the extra one only tells that more follow
 * (ADR-0008). One index range scan per page (ADR-0020).
 */
public final class PostgresItinerarySearchQuery implements ItinerarySearchQuery {

    private final JdbcClient jdbc;

    public PostgresItinerarySearchQuery(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    private record Row(long id, ItineraryView view) {}

    @Override
    public SearchSlice find(SearchCriteria criteria, PagePosition after, int limit, Instant now) {
        ItinerarySearchSql.SqlQuery query = ItinerarySearchSql.page(criteria, after, limit + 1, now);
        List<Row> rows = jdbc.sql(query.sql())
                .params(query.params())
                .query((rs, n) -> new Row(rs.getLong("id"), ReadModelRows.itinerary(rs)))
                .list();
        if (rows.size() <= limit) {
            return new SearchSlice(rows.stream().map(Row::view).toList(), null);
        }
        List<Row> page = rows.subList(0, limit);
        Row last = page.get(limit - 1);
        return new SearchSlice(
                page.stream().map(Row::view).toList(),
                new PagePosition(last.view().finalPrice(), last.id()));
    }
}
