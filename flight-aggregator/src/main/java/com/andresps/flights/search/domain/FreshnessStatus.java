package com.andresps.flights.search.domain;

/** How current a day's snapshot is (spec 004 `day_snapshot`, spec 005 AC-10). */
public enum FreshnessStatus {
    /** Every configured provider contributed, and the latest run refreshed it. */
    COMPLETE,
    /** Published without some providers (listed in {@code missingProviders}). */
    INCOMPLETE,
    /** The latest run could not refresh it; the previous snapshot is kept (ADR-0017). Takes precedence. */
    STALE
}
