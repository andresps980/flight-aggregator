package com.andresps.flights.search.domain;

import java.time.LocalDate;

/** A departure day that still has flights to depart, with its freshness. */
public record CatalogDate(LocalDate date, Freshness freshness) {}
