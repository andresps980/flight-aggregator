package com.andresps.flights.greeting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PersonNameTest {

    @Test
    @DisplayName("strips surrounding whitespace")
    void stripsWhitespace() {
        assertThat(new PersonName("  Ana  ").value()).isEqualTo("Ana");
    }

    @Test
    @DisplayName("accepts a name of exactly the maximum length")
    void acceptsMaxLength() {
        String name = "a".repeat(PersonName.MAX_LENGTH);

        assertThat(new PersonName(name).value()).hasSize(PersonName.MAX_LENGTH);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("rejects null or blank names")
    void rejectsBlank(String value) {
        assertThatThrownBy(() -> new PersonName(value))
                .isInstanceOf(InvalidPersonNameException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("rejects names longer than the maximum length")
    void rejectsTooLong() {
        String name = "a".repeat(PersonName.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new PersonName(name))
                .isInstanceOf(InvalidPersonNameException.class)
                .hasMessageContaining("at most");
    }
}
