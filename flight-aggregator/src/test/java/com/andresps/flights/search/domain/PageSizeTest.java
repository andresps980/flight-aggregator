package com.andresps.flights.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageSizeTest {

    @Test
    @DisplayName("AC-6: limit defaults to 20 when absent or blank")
    void defaultsTo20() {
        assertThat(PageSize.parse(null).value()).isEqualTo(20);
        assertThat(PageSize.parse(" ").value()).isEqualTo(20);
    }

    @ParameterizedTest(name = "limit={0}")
    @ValueSource(strings = {"1", "20", "100", " 50 "})
    @DisplayName("AC-6: limit 1-100 is accepted")
    void acceptsTheRange(String limit) {
        assertThat(PageSize.parse(limit).value()).isEqualTo(Integer.parseInt(limit.trim()));
    }

    @ParameterizedTest(name = "limit={0}")
    @ValueSource(strings = {"0", "101", "-1", "abc", "2.5", "99999999999"})
    @DisplayName("AC-14: limit not an integer or outside 1-100 is rejected")
    void rejectsOutOfRange(String limit) {
        assertThatThrownBy(() -> PageSize.parse(limit))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("limit");
    }
}
