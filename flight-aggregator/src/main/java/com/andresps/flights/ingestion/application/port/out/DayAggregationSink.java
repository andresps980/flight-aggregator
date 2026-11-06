package com.andresps.flights.ingestion.application.port.out;

import com.andresps.flights.ingestion.domain.DayAggregation;
import com.andresps.flights.ingestion.domain.DayPublication;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Where each finished day goes (spec 003, spec 004). Called from several threads, once per day. A day published with
 * missing providers must not wipe their previous good data (ADR-0017). Implementations report failures as
 * {@code WRITE_FAILED} rather than throwing.
 */
public interface DayAggregationSink {

    /** A day with at least one contributing provider. */
    DayPublication publish(DayAggregation day);

    /** A horizon day where every provider failed: an existing snapshot is kept, marked stale (AC-6). */
    DayPublication unavailable(UUID runId, LocalDate day);
}
