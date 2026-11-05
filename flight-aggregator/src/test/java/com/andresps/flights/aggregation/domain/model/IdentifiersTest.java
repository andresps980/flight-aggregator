package com.andresps.flights.aggregation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** AC-24: identifier value objects validate and normalise their input. */
class IdentifiersTest {

    @Nested
    class AirportCodeTest {

        @ParameterizedTest
        @CsvSource({"MAD,MAD", "mad,MAD", "' bcn ',BCN"})
        @DisplayName("AC-24: accepts three-letter codes, normalised to upper case and trimmed")
        void acceptsThreeLetters(String raw, String expected) {
            assertThat(new AirportCode(raw).value()).isEqualTo(expected);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "MA", "MADR", "M4D", "M-D", "MÁD"})
        @DisplayName("AC-24: rejects anything that is not three letters A-Z")
        void rejectsInvalid(String raw) {
            assertThatThrownBy(() -> new AirportCode(raw)).isInstanceOf(InvalidFlightDataException.class);
        }
    }

    @Nested
    class AirlineCodeTest {

        @ParameterizedTest
        @CsvSource({"IB,IB", "u2,U2", "9W,9W", "' vy ',VY"})
        @DisplayName("AC-24: accepts two alphanumeric characters, normalised to upper case and trimmed")
        void acceptsTwoAlphanumerics(String raw, String expected) {
            assertThat(new AirlineCode(raw).value()).isEqualTo(expected);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "I", "IBE", "I-", "Ñ1"})
        @DisplayName("AC-24: rejects anything that is not two characters A-Z/0-9")
        void rejectsInvalid(String raw) {
            assertThatThrownBy(() -> new AirlineCode(raw)).isInstanceOf(InvalidFlightDataException.class);
        }
    }

    @Nested
    class FlightNumberTest {

        @ParameterizedTest
        @CsvSource({"IB3456,IB,3456", "ib1,IB,1", "' U21234 ',U2,1234", "9W123,9W,123"})
        @DisplayName("AC-24: parses an airline code followed by 1-4 digits")
        void parsesValid(String raw, String airline, String number) {
            FlightNumber flightNumber = FlightNumber.parse(raw);

            assertThat(flightNumber.airline()).isEqualTo(new AirlineCode(airline));
            assertThat(flightNumber.number()).isEqualTo(number);
            assertThat(flightNumber).hasToString(airline + number);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"IB", "IB12345", "IBX1", "I-12", "IB 12"})
        @DisplayName("AC-24: rejects a missing airline code or a number that is not 1-4 digits")
        void rejectsInvalid(String raw) {
            assertThatThrownBy(() -> FlightNumber.parse(raw)).isInstanceOf(InvalidFlightDataException.class);
        }

        @Test
        @DisplayName("AC-24: rejects a null airline")
        void rejectsNullAirline() {
            assertThatThrownBy(() -> new FlightNumber(null, "123")).isInstanceOf(InvalidFlightDataException.class);
        }
    }

    @Nested
    class ProviderIdTest {

        @Test
        @DisplayName("AC-24: trims the provider id")
        void trims() {
            assertThat(new ProviderId("  mock-alpha ").value()).isEqualTo("mock-alpha");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("AC-24: rejects a blank provider id")
        void rejectsBlank(String raw) {
            assertThatThrownBy(() -> new ProviderId(raw)).isInstanceOf(InvalidFlightDataException.class);
        }

        @Test
        @DisplayName("AC-5: provider ids are ordered lexicographically")
        void ordersLexicographically() {
            assertThat(new ProviderId("mock-alpha")).isLessThan(new ProviderId("mock-beta"));
        }
    }
}
