package com.andresps.gdsmock.inventory.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The UTC days served: the {@code days} days after the reference date (spec 002, AC-2).
 *
 * @param from first day (reference date + 1)
 * @param to last day, inclusive
 */
public record Horizon(LocalDate referenceDate, LocalDate from, LocalDate to) {

    public Horizon {
        Objects.requireNonNull(referenceDate, "referenceDate");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public static Horizon after(LocalDate referenceDate, int days) {
        return new Horizon(referenceDate, referenceDate.plusDays(1), referenceDate.plusDays(days));
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(from) && !day.isAfter(to);
    }

    public List<LocalDate> days() {
        return from.datesUntil(to.plusDays(1)).toList();
    }

    @Override
    public String toString() {
        return from + ".." + to;
    }
}
