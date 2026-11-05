package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import static com.andresps.gdsmock.support.WorldFixtures.ALPHA;
import static com.andresps.gdsmock.support.WorldFixtures.PROVIDERS;
import static com.andresps.gdsmock.support.WorldFixtures.REFERENCE_DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.gdsmock.inventory.domain.generator.DateOutsideHorizonException;
import com.andresps.gdsmock.inventory.domain.model.Horizon;
import com.andresps.gdsmock.shared.domain.InvalidRequestException;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedRequestResolverTest {

    private final FeedRequestResolver resolver =
            new FeedRequestResolver(new LinkedHashSet<>(PROVIDERS), () -> Horizon.after(REFERENCE_DATE, 7));

    @Test
    @DisplayName("AC-13: a known provider without a date asks for the whole horizon")
    void wholeHorizon() {
        assertThat(resolver.resolve("mock-alpha", null)).isEqualTo(new FeedRequest(ALPHA, Optional.empty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-06", "2026-11-12"})
    @DisplayName("AC-13: a date inside the horizon selects that partition day (first and last day included)")
    void dateInsideHorizon(String date) {
        assertThat(resolver.resolve("mock-alpha", date).date()).contains(LocalDate.parse(date));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-05", "2026-11-13", "2025-01-01"})
    @DisplayName("AC-14 (error): a date outside the horizon is an invalid request")
    void dateOutsideHorizon(String date) {
        assertThatThrownBy(() -> resolver.resolve("mock-alpha", date))
                .isInstanceOf(DateOutsideHorizonException.class)
                .isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13-01", "10/11/2026", "tomorrow", ""})
    @DisplayName("AC-14 (error): a date not in YYYY-MM-DD is an invalid request")
    void malformedDate(String date) {
        assertThatThrownBy(() -> resolver.resolve("mock-alpha", date))
                .isInstanceOf(InvalidDateException.class)
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    @DisplayName("AC-14 (error): an unknown provider is rejected before the date is looked at")
    void unknownProvider() {
        assertThatThrownBy(() -> resolver.resolve("mock-delta", "not-a-date"))
                .isInstanceOf(UnknownProviderException.class)
                .hasMessageContaining("mock-delta");
    }

    @Test
    @DisplayName("AC-20: the stats endpoint validates its optional date the same way")
    void resolveDateOnly() {
        assertThat(resolver.resolveDate(null)).isEmpty();
        assertThat(resolver.resolveDate("2026-11-08")).contains(LocalDate.of(2026, 11, 8));
        assertThatThrownBy(() -> resolver.resolveDate("2026-11-30")).isInstanceOf(DateOutsideHorizonException.class);
    }
}
