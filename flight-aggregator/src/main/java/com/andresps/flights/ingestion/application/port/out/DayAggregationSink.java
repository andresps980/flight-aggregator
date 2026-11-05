package com.andresps.flights.ingestion.application.port.out;

import com.andresps.flights.ingestion.domain.DayAggregation;

/**
 * Where each day's aggregation goes once the day finishes (in memory now, PostgreSQL in the next spec). Called from
 * several threads, once per published day. A day published with missing providers must not wipe their previous good
 * data for that day (spec 003, requirements for the persistence spec).
 */
public interface DayAggregationSink {

    void publish(DayAggregation day);
}
