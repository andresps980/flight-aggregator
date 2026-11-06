package com.andresps.flights.ingestion.domain;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.BETA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 004 / ADR-0017: when a publication replaces a day's snapshot and when the snapshot is kept stale. */
class SnapshotPolicyTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-06");
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final SnapshotPolicy POLICY = new SnapshotPolicy(Duration.ofHours(2));

    private static TreeSet<ProviderId> providers(ProviderId... ids) {
        return new TreeSet<>(Set.of(ids));
    }

    private static Optional<DaySnapshot> snapshot(Instant staleSince, ProviderId... ids) {
        return Optional.of(new DaySnapshot(DAY, UUID.randomUUID(), providers(ids), NOW.minusSeconds(900), staleSince));
    }

    @Test
    @DisplayName("AC-2: a complete publication always replaces, whatever the existing snapshot")
    void completeReplaces() {
        assertThat(POLICY.decide(snapshot(null, ALPHA, BETA, GAMMA), providers(ALPHA, BETA, GAMMA), NOW))
                .isEqualTo(SnapshotDecision.REPLACE);
        assertThat(POLICY.decide(snapshot(NOW.minusSeconds(60), ALPHA), providers(ALPHA, BETA, GAMMA), NOW))
                .isEqualTo(SnapshotDecision.REPLACE);
    }

    @Test
    @DisplayName("AC-4: a partial publication of a day without a snapshot is written")
    void partialWithoutSnapshotIsWritten() {
        assertThat(POLICY.decide(Optional.empty(), providers(ALPHA), NOW)).isEqualTo(SnapshotDecision.REPLACE);
    }

    @Test
    @DisplayName("AC-4: a partial publication replaces a snapshot whose providers are all among its contributors")
    void partialSupersetReplaces() {
        assertThat(POLICY.decide(snapshot(null, ALPHA), providers(ALPHA, BETA), NOW))
                .isEqualTo(SnapshotDecision.REPLACE);
        assertThat(POLICY.decide(snapshot(null, ALPHA, BETA), providers(ALPHA, BETA), NOW))
                .as("same providers, fresher data")
                .isEqualTo(SnapshotDecision.REPLACE);
    }

    @Test
    @DisplayName("AC-3: a publication missing a provider of the existing snapshot keeps it stale")
    void partialMissingASnapshotProviderKeeps() {
        assertThat(POLICY.decide(snapshot(null, ALPHA, BETA, GAMMA), providers(ALPHA, GAMMA), NOW))
                .isEqualTo(SnapshotDecision.KEEP_STALE);
        assertThat(POLICY.decide(snapshot(null, ALPHA, BETA), providers(ALPHA, GAMMA), NOW))
                .as("different, not more complete")
                .isEqualTo(SnapshotDecision.KEEP_STALE);
    }

    @Test
    @DisplayName("AC-5: a snapshot stale for longer than max-stale-age is replaced by a partial publication")
    void staleTooLongIsReplaced() {
        assertThat(POLICY.decide(snapshot(NOW.minus(Duration.ofHours(2)), ALPHA, BETA, GAMMA), providers(ALPHA), NOW))
                .as("exactly max-stale-age")
                .isEqualTo(SnapshotDecision.REPLACE);
        assertThat(POLICY.decide(
                        snapshot(NOW.minus(Duration.ofMinutes(119)), ALPHA, BETA, GAMMA), providers(ALPHA), NOW))
                .isEqualTo(SnapshotDecision.KEEP_STALE);
    }

    @Test
    @DisplayName("AC-5: max-stale-age must be positive; contributors must not be empty")
    void invariants() {
        assertThatThrownBy(() -> new SnapshotPolicy(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> POLICY.decide(Optional.empty(), providers(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
