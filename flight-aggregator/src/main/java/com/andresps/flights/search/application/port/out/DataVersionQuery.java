package com.andresps.flights.search.application.port.out;

/**
 * The current version of the read model: it changes in the same transaction as every change to the searchable data
 * (ADR-0022). Owned by the search application; read by the cache decorators to key their entries.
 */
public interface DataVersionQuery {

    long current();
}
