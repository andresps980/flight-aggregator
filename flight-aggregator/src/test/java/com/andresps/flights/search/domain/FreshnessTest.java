package com.andresps.flights.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FreshnessTest {

    private static final Instant PUBLISHED = Instant.parse("2026-11-06T10:00:00Z");
    private static final Instant STALE_SINCE = Instant.parse("2026-11-06T10:15:00Z");

    @Test
    @DisplayName("AC-10: a complete, fresh day is COMPLETE")
    void complete() {
        assertThat(Freshness.of(true, PUBLISHED, List.of(), null).status()).isEqualTo(FreshnessStatus.COMPLETE);
    }

    @Test
    @DisplayName("AC-10: a day with missing providers is INCOMPLETE")
    void incomplete() {
        Freshness freshness = Freshness.of(false, PUBLISHED, List.of("mock-beta"), null);

        assertThat(freshness.status()).isEqualTo(FreshnessStatus.INCOMPLETE);
        assertThat(freshness.missingProviders()).containsExactly("mock-beta");
    }

    @Test
    @DisplayName("AC-10: a stale day is STALE, also when it is incomplete (stale takes precedence)")
    void staleWins() {
        assertThat(Freshness.of(true, PUBLISHED, List.of(), STALE_SINCE).status())
                .isEqualTo(FreshnessStatus.STALE);
        assertThat(Freshness.of(false, PUBLISHED, List.of("mock-beta"), STALE_SINCE)
                        .status())
                .isEqualTo(FreshnessStatus.STALE);
    }
}
