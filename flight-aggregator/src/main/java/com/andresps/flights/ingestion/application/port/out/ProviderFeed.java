package com.andresps.flights.ingestion.application.port.out;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.ProviderPartition;
import java.time.LocalDate;

/**
 * One GDS provider's feed, one day at a time. Implementations own their transport, format and resilience (timeouts,
 * retries, breaker).
 *
 * <p>Contract: {@link #fetch} returns a <b>fully parsed</b> partition or throws {@link PartitionFetchException}; it never
 * returns part of a day (ADR-0015). It is safe to call concurrently for different days. If the calling thread is
 * interrupted (run deadline), it stops promptly, without further attempts, and throws.
 */
public interface ProviderFeed {

    ProviderId provider();

    ProviderPartition fetch(LocalDate day);

    /** Current breaker state; {@link CircuitState#CLOSED} for feeds without a breaker. */
    CircuitState circuitState();
}
