package com.andresps.flights.search.infrastructure.adapter.out.persistence;

import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.SearchCriteria;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The keyset page query of spec 005 / ADR-0008, built from the filters present. Every value is a placeholder. Codes
 * are cast to {@code bpchar} so they compare with the {@code char(n)} columns without casting the column, which would
 * make the indexes of ADR-0020 unusable. The statement always ends with {@code LIMIT ?} (the performance test appends
 * {@code OFFSET} to compare).
 */
public final class ItinerarySearchSql {

    static final String COLUMNS = """
            SELECT i.id, i.itinerary_key, i.origin, i.destination, i.departure_date, i.departure_at, i.arrival_at,
                   i.airline, i.stops, i.base_price, i.final_price, i.selected_provider, i.providers, i.segments,
                   s.complete, s.published_at, s.missing_providers, s.stale_since
            FROM itinerary i JOIN day_snapshot s ON s.departure_date = i.departure_date
            """;

    private ItinerarySearchSql() {}

    public record SqlQuery(String sql, List<Object> params) {

        public SqlQuery {
            params = List.copyOf(params);
        }
    }

    /** Rows strictly after {@code after} ({@code null}: from the start), at most {@code rows} of them. */
    public static SqlQuery page(SearchCriteria criteria, PagePosition after, int rows, Instant now) {
        StringBuilder sql = new StringBuilder(COLUMNS).append("WHERE i.departure_at > ?");
        List<Object> params = new ArrayList<>();
        params.add(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        if (criteria.date() != null) {
            sql.append(" AND i.departure_date = ?");
            params.add(criteria.date());
        }
        if (criteria.origin() != null) {
            sql.append(" AND i.origin = ?::bpchar");
            params.add(criteria.origin().value());
        }
        if (criteria.destination() != null) {
            sql.append(" AND i.destination = ?::bpchar");
            params.add(criteria.destination().value());
        }
        if (criteria.carrier() != null) {
            sql.append(" AND i.airline = ?::bpchar");
            params.add(criteria.carrier().value());
        }
        if (criteria.maxPrice() != null) {
            sql.append(" AND i.final_price <= ?");
            params.add(criteria.maxPrice());
        }
        if (after != null) {
            sql.append(" AND (i.final_price, i.id) > (?, ?)");
            params.add(after.finalPrice());
            params.add(after.id());
        }
        sql.append(" ORDER BY i.final_price, i.id LIMIT ?");
        params.add(rows);
        return new SqlQuery(sql.toString(), params);
    }
}
