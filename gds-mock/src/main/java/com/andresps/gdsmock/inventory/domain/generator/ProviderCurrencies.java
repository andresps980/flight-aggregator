package com.andresps.gdsmock.inventory.domain.generator;

import com.andresps.gdsmock.inventory.domain.model.FeedCurrency;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

/**
 * The currencies each provider prices in (spec 008, ADR-0025); a provider not listed prices in EUR only. Each record's
 * currency is drawn uniformly from its provider's list with a random of its own, seeded by the caller from the world
 * seed and the record, so the draw never touches the world's generation stream.
 */
public final class ProviderCurrencies {

    private static final List<FeedCurrency> EUR_ONLY = List.of(FeedCurrency.EUR);
    private static final ProviderCurrencies EUR_EVERYWHERE = new ProviderCurrencies(Map.of());

    private final Map<ProviderId, List<FeedCurrency>> byProvider;

    private ProviderCurrencies(Map<ProviderId, List<FeedCurrency>> byProvider) {
        this.byProvider = byProvider;
    }

    /** @throws IllegalArgumentException if a provider's list is empty */
    public static ProviderCurrencies of(Map<ProviderId, List<FeedCurrency>> byProvider) {
        byProvider.forEach((provider, currencies) -> {
            if (currencies == null || currencies.isEmpty()) {
                throw new IllegalArgumentException("the currencies of " + provider.value() + " must not be empty");
            }
        });
        return new ProviderCurrencies(Map.copyOf(byProvider.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())))));
    }

    /** Every provider in EUR: the worlds generated before spec 008. */
    public static ProviderCurrencies eurOnly() {
        return EUR_EVERYWHERE;
    }

    public List<FeedCurrency> currencies(ProviderId provider) {
        return byProvider.getOrDefault(provider, EUR_ONLY);
    }

    /** The record priced in its drawn currency; untouched when its provider prices in EUR only. */
    ProviderRecord price(ProviderRecord record, long recordSeed) {
        List<FeedCurrency> currencies = currencies(record.provider());
        if (currencies.equals(EUR_ONLY)) {
            return record;
        }
        int pick = currencies.size() == 1 ? 0 : new SplittableRandom(recordSeed).nextInt(currencies.size());
        return record.pricedIn(currencies.get(pick));
    }
}
