package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.inventory.domain.generator.DateOutsideHorizonException;
import com.andresps.gdsmock.inventory.domain.model.Horizon;
import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Validates the provider id and optional {@code date} of a request. Shared by the feed controller and the chaos
 * interceptor, so invalid requests are rejected before any chaos is applied (spec 002, AC-14).
 */
public final class FeedRequestResolver {

    private final Set<ProviderId> providers;
    private final Supplier<Horizon> horizon;

    public FeedRequestResolver(Set<ProviderId> providers, Supplier<Horizon> horizon) {
        this.providers = Set.copyOf(providers);
        this.horizon = horizon;
    }

    /**
     * @throws UnknownProviderException if no provider has this id (checked first)
     * @throws InvalidDateException if the date is not {@code YYYY-MM-DD}
     * @throws DateOutsideHorizonException if the date is not a horizon day
     */
    public FeedRequest resolve(String providerId, String date) {
        ProviderId provider = providers.stream()
                .filter(p -> p.value().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new UnknownProviderException(providerId));
        return new FeedRequest(provider, resolveDate(date));
    }

    /** Validates an optional date parameter; {@code null} means the whole horizon. */
    public Optional<LocalDate> resolveDate(String date) {
        if (date == null) {
            return Optional.empty();
        }
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new InvalidDateException(date);
        }
        Horizon current = horizon.get();
        if (!current.contains(day)) {
            throw new DateOutsideHorizonException(day, current);
        }
        return Optional.of(day);
    }
}
