package com.andresps.flights.ingestion.application.service;

import com.andresps.flights.ingestion.application.port.out.IngestionRunRepository;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.RunStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory test double of the run repository. */
final class InMemoryRunRepository implements IngestionRunRepository {

    private final Map<UUID, IngestionRun> runs = new ConcurrentHashMap<>();

    @Override
    public void create(IngestionRun run) {
        if (runs.putIfAbsent(run.id(), run) != null) {
            throw new IllegalStateException("duplicate run " + run.id());
        }
    }

    @Override
    public void update(IngestionRun run) {
        if (runs.replace(run.id(), run) == null) {
            throw new IllegalStateException("unknown run " + run.id());
        }
    }

    @Override
    public Optional<IngestionRun> findById(UUID id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public Optional<IngestionRun> findLatest() {
        return runs.values().stream().max(Comparator.comparing(IngestionRun::startedAt));
    }

    @Override
    public Optional<UUID> findRunning() {
        return runs.values().stream()
                .filter(run -> run.status() == RunStatus.RUNNING)
                .max(Comparator.comparing(IngestionRun::startedAt))
                .map(IngestionRun::id);
    }

    @Override
    public int abortRunning(Instant at) {
        int[] aborted = {0};
        runs.replaceAll((id, run) -> {
            if (run.status() != RunStatus.RUNNING) {
                return run;
            }
            aborted[0]++;
            return run.aborted(at);
        });
        return aborted[0];
    }

    @Override
    public int deleteStartedBefore(Instant instant) {
        int before = runs.size();
        runs.values().removeIf(run -> run.startedAt().isBefore(instant));
        return before - runs.size();
    }

    void put(IngestionRun run) {
        runs.put(run.id(), run);
    }

    int size() {
        return runs.size();
    }
}
