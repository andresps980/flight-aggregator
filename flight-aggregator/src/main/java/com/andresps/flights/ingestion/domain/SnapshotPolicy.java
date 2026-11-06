package com.andresps.flights.ingestion.domain;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;

/**
 * When a day's publication replaces its stored snapshot (spec 004, ADR-0017). It replaces when it is complete (every
 * configured provider contributed, even if the snapshot lists a provider no longer configured), when there is no
 * snapshot, when its contributing providers include every provider of the snapshot (it is at least as complete and
 * fresher), or when the snapshot has been stale for at least {@code maxStaleAge}. Otherwise the snapshot is kept: a
 * missing provider's previous data is never silently wiped.
 */
public final class SnapshotPolicy {

    private final Duration maxStaleAge;

    public SnapshotPolicy(Duration maxStaleAge) {
        Objects.requireNonNull(maxStaleAge, "maxStaleAge");
        if (maxStaleAge.isNegative() || maxStaleAge.isZero()) {
            throw new IllegalArgumentException("max-stale-age must be positive, got " + maxStaleAge);
        }
        this.maxStaleAge = maxStaleAge;
    }

    public SnapshotDecision decide(
            Optional<DaySnapshot> existing,
            SortedSet<ProviderId> contributing,
            SortedSet<ProviderId> missing,
            Instant now) {
        if (contributing.isEmpty()) {
            throw new IllegalArgumentException("A publication needs at least one contributing provider");
        }
        if (missing.isEmpty() || existing.isEmpty()) {
            return SnapshotDecision.REPLACE;
        }
        DaySnapshot snapshot = existing.get();
        if (contributing.containsAll(snapshot.providers())) {
            return SnapshotDecision.REPLACE;
        }
        if (snapshot.stale()
                && !Duration.between(snapshot.staleSince(), now)
                        .minus(maxStaleAge)
                        .isNegative()) {
            return SnapshotDecision.REPLACE;
        }
        return SnapshotDecision.KEEP_STALE;
    }
}
