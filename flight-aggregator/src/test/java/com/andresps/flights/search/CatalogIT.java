package com.andresps.flights.search;

import static com.andresps.flights.search.SearchRows.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec 005 AC-17 over real HTTP: {@code GET /api/v1/catalog}. "Now" is 2026-11-06T12:00:00Z. */
class CatalogIT extends SearchSpringTest {

    private JsonNode catalog() {
        ResponseEntity<JsonNode> response = get("/api/v1/catalog");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    @DisplayName("AC-17: sorted distinct origins, destinations, carriers, dates with freshness, and routes")
    void listsWhatCanBeSearched() {
        rows.insertAll(List.of(
                row("MAD", "JFK", "IB", "2026-11-07T10:00:00Z", "400.00"),
                row("MAD", "JFK", "VY", "2026-11-07T11:00:00Z", "350.00"),
                row("MAD", "LHR", "IB", "2026-11-08T08:00:00Z", "120.00"),
                row("BCN", "LHR", "U2", "2026-11-06T18:00:00Z", "90.00"),
                // departed: contributes nothing
                row("AMS", "CDG", "KL", "2026-11-06T09:00:00Z", "80.00")));
        rows.completeDay(LocalDate.parse("2026-11-06"));
        rows.completeDay(LocalDate.parse("2026-11-07"));
        rows.incompleteDay(LocalDate.parse("2026-11-08"), "mock-beta");
        rows.publish();

        JsonNode catalog = catalog();

        assertThat(catalog.get("origins").toString()).isEqualTo("[\"BCN\",\"MAD\"]");
        assertThat(catalog.get("destinations").toString()).isEqualTo("[\"JFK\",\"LHR\"]");
        assertThat(catalog.get("carriers").toString()).isEqualTo("[\"IB\",\"U2\",\"VY\"]");
        assertThat(catalog.get("routes").toString())
                .isEqualTo("[{\"origin\":\"BCN\",\"destinations\":[\"LHR\"]},"
                        + "{\"origin\":\"MAD\",\"destinations\":[\"JFK\",\"LHR\"]}]");
        JsonNode dates = catalog.get("dates");
        assertThat(dates.size()).isEqualTo(3);
        assertThat(dates.get(0).get("date").asString()).isEqualTo("2026-11-06");
        assertThat(dates.get(0).get("freshness").get("status").asString()).isEqualTo("COMPLETE");
        assertThat(dates.get(2).get("date").asString()).isEqualTo("2026-11-08");
        assertThat(dates.get(2).get("freshness").get("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(dates.get(2).get("freshness").get("missingProviders").get(0).asString())
                .isEqualTo("mock-beta");
    }

    @Test
    @DisplayName("AC-17: a day whose flights have all departed is not listed")
    void departedDayIsNotListed() {
        rows.insert(row("BCN", "LHR", "U2", "2026-11-06T09:00:00Z", "90.00"));
        rows.insert(row("MAD", "JFK", "IB", "2026-11-07T10:00:00Z", "400.00"));
        rows.completeDay(LocalDate.parse("2026-11-06"));
        rows.completeDay(LocalDate.parse("2026-11-07"));
        rows.publish();

        JsonNode catalog = catalog();

        assertThat(catalog.get("dates").size()).isEqualTo(1);
        assertThat(catalog.get("dates").get(0).get("date").asString()).isEqualTo("2026-11-07");
        assertThat(catalog.get("origins").toString()).isEqualTo("[\"MAD\"]");
    }

    @Test
    @DisplayName("AC-17: a stale day shows its freshness")
    void staleDay() {
        rows.insert(row("MAD", "JFK", "IB", "2026-11-07T10:00:00Z", "400.00"));
        rows.staleDay(LocalDate.parse("2026-11-07"), Instant.parse("2026-11-06T11:00:00Z"));
        rows.publish();

        JsonNode freshness = catalog().get("dates").get(0).get("freshness");

        assertThat(freshness.get("status").asString()).isEqualTo("STALE");
        assertThat(freshness.get("staleSince").asString()).isEqualTo("2026-11-06T11:00:00Z");
    }

    @Test
    @DisplayName("AC-17: with no data every list is empty")
    void empty() {
        JsonNode catalog = catalog();

        for (String list : List.of("origins", "destinations", "carriers", "dates", "routes")) {
            assertThat(catalog.get(list).size()).as(list).isZero();
        }
    }
}
