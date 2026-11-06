package com.andresps.flights.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.model.AirlineCode;
import com.andresps.flights.aggregation.domain.model.AirportCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchCriteriaTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-06");

    private static SearchCriteria parse(
            String origin, String destination, String date, String maxPrice, String carrier) {
        return SearchCriteria.parse(origin, destination, date, maxPrice, carrier, TODAY);
    }

    @Test
    @DisplayName("AC-1: every filter is optional; absent and blank parameters mean no filter")
    void absentAndBlankMeanNoFilter() {
        SearchCriteria none = parse(null, null, null, null, null);
        SearchCriteria blank = parse("", " ", "", "  ", "");

        assertThat(none).isEqualTo(SearchCriteria.none());
        assertThat(blank).isEqualTo(SearchCriteria.none());
        assertThat(none.origin()).isNull();
        assertThat(none.maxPrice()).isNull();
    }

    @Test
    @DisplayName("AC-1: parses every filter")
    void parsesEveryFilter() {
        SearchCriteria criteria = parse("MAD", "JFK", "2026-11-08", "650.5", "IB");

        assertThat(criteria.origin()).isEqualTo(new AirportCode("MAD"));
        assertThat(criteria.destination()).isEqualTo(new AirportCode("JFK"));
        assertThat(criteria.date()).isEqualTo(LocalDate.parse("2026-11-08"));
        assertThat(criteria.maxPrice()).isEqualTo(new BigDecimal("650.50"));
        assertThat(criteria.carrier()).isEqualTo(new AirlineCode("IB"));
    }

    @Test
    @DisplayName("AC-2: codes are case-insensitive and trimmed; maxPrice 100 and 100.00 are the same filter")
    void normalizes() {
        SearchCriteria lower = parse(" mad ", "jfk", null, "100", "ib");
        SearchCriteria upper = parse("MAD", "JFK", null, "100.00", "IB");

        assertThat(lower).isEqualTo(upper);
        assertThat(lower.canonical()).isEqualTo(upper.canonical());
        assertThat(lower.fingerprint()).isEqualTo(upper.fingerprint());
    }

    @Test
    @DisplayName("AC-2 / ADR-0021: the fingerprint is 16 hex characters and differs when any filter differs")
    void fingerprintTracksEveryFilter() {
        SearchCriteria base = parse("MAD", "JFK", "2026-11-08", "650", "IB");

        assertThat(base.fingerprint()).matches("[0-9a-f]{16}");
        assertThat(base.fingerprint())
                .isEqualTo(parse("MAD", "JFK", "2026-11-08", "650.00", "IB").fingerprint());
        assertThat(parse("BCN", "JFK", "2026-11-08", "650", "IB").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(parse("MAD", "LHR", "2026-11-08", "650", "IB").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(parse("MAD", "JFK", "2026-11-09", "650", "IB").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(parse("MAD", "JFK", "2026-11-08", "650.01", "IB").fingerprint())
                .isNotEqualTo(base.fingerprint());
        assertThat(parse("MAD", "JFK", "2026-11-08", "650", "VY").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(parse("MAD", "JFK", "2026-11-08", "650", null).fingerprint()).isNotEqualTo(base.fingerprint());
        // a value cannot move between filters and keep the fingerprint
        assertThat(parse("MAD", null, null, null, null).fingerprint())
                .isNotEqualTo(parse(null, "MAD", null, null, null).fingerprint());
    }

    @ParameterizedTest(name = "origin={0}")
    @ValueSource(strings = {"MA", "MADR", "M4D", "12A", "MÁD"})
    @DisplayName("AC-12: origin must be a 3-letter IATA code")
    void rejectsBadOrigin(String origin) {
        assertThatThrownBy(() -> parse(origin, null, null, null, null))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("origin");
    }

    @ParameterizedTest(name = "destination={0}")
    @ValueSource(strings = {"JF", "JFKX", "J-K"})
    @DisplayName("AC-12: destination must be a 3-letter IATA code")
    void rejectsBadDestination(String destination) {
        assertThatThrownBy(() -> parse(null, destination, null, null, null))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("destination");
    }

    @ParameterizedTest(name = "carrier={0}")
    @ValueSource(strings = {"I", "IBE", "I_", "Ñ1"})
    @DisplayName("AC-12: carrier must be 2 characters A-Z/0-9")
    void rejectsBadCarrier(String carrier) {
        assertThatThrownBy(() -> parse(null, null, null, null, carrier))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("carrier");
    }

    @ParameterizedTest(name = "maxPrice={0}")
    @ValueSource(strings = {"abc", "-1", "-0.01", "10.001", "1000000000", "1e3", "12,50", "NaN"})
    @DisplayName("AC-13: maxPrice must be a non-negative amount with at most 2 decimals below 1,000,000,000")
    void rejectsBadMaxPrice(String maxPrice) {
        assertThatThrownBy(() -> parse(null, null, null, maxPrice, null))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("maxPrice");
    }

    @ParameterizedTest(name = "maxPrice={0}")
    @CsvSource({"0, 0.00", "0.5, 0.50", "999999999.99, 999999999.99", "100, 100.00"})
    @DisplayName("AC-13: accepts the boundaries, normalized to 2 decimals")
    void acceptsMaxPriceBoundaries(String maxPrice, String expected) {
        assertThat(parse(null, null, null, maxPrice, null).maxPrice()).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest(name = "date={0}")
    @ValueSource(strings = {"2026-13-01", "06/11/2026", "2026-11-6", "tomorrow", "2026-02-30"})
    @DisplayName("AC-15: date must be an ISO date")
    void rejectsMalformedDate(String date) {
        assertThatThrownBy(() -> parse(null, null, date, null, null))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("date");
    }

    @ParameterizedTest(name = "date={0}")
    @ValueSource(strings = {"2026-11-04", "2027-11-07", "1999-01-01"})
    @DisplayName("AC-15: date outside [today - 1, today + 365] is rejected")
    void rejectsDateOutOfRange(String date) {
        assertThatThrownBy(() -> parse(null, null, date, null, null))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("date");
    }

    @ParameterizedTest(name = "date={0}")
    @ValueSource(strings = {"2026-11-05", "2026-11-06", "2027-11-06"})
    @DisplayName("AC-15: the range boundaries are accepted")
    void acceptsDateRangeBoundaries(String date) {
        assertThat(parse(null, null, date, null, null).date()).isEqualTo(LocalDate.parse(date));
    }

    @Test
    @DisplayName("Q9: origin equal to destination is a valid (empty) search, not an error")
    void originEqualToDestinationIsValid() {
        assertThat(parse("MAD", "mad", null, null, null).destination()).isEqualTo(new AirportCode("MAD"));
    }
}
