package com.andresps.gdsmock.inventory.domain.generator;

import static com.andresps.gdsmock.support.WorldFixtures.CLOCK;
import static com.andresps.gdsmock.support.WorldFixtures.PROVIDERS;
import static com.andresps.gdsmock.support.WorldFixtures.generator;
import static com.andresps.gdsmock.support.WorldFixtures.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.gdsmock.inventory.domain.model.FeedCurrency;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.WorldItinerary;
import com.andresps.gdsmock.inventory.domain.stats.WorldStatisticsCalculator;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec 008 AC-12/AC-13: each record's currency is drawn from its provider's list with a random derived from the world
 * seed and the record, apart from the generation stream, so the world itself never changes.
 */
class WorldCurrencyTest {

    private static final int VOLUME = 1_400;
    private static final FeedCurrency EUR = FeedCurrency.EUR;
    private static final FeedCurrency USD = new FeedCurrency(Currency.getInstance("USD"), new BigDecimal("1.10"));
    private static final FeedCurrency GBP = new FeedCurrency(Currency.getInstance("GBP"), new BigDecimal("0.85"));
    private static final List<FeedCurrency> MIXED = List.of(EUR, USD, GBP);

    private static List<WorldItinerary> eurWorld;
    private static List<WorldItinerary> mixedWorld;

    private static WorldGenerator mixedGenerator() {
        return new WorldGenerator(
                settings(VOLUME),
                PROVIDERS,
                ProviderCurrencies.of(PROVIDERS.stream().collect(Collectors.toMap(p -> p, p -> MIXED))),
                CLOCK);
    }

    @BeforeAll
    static void generate() {
        eurWorld = generator(settings(VOLUME)).itineraries().toList();
        mixedWorld = mixedGenerator().itineraries().toList();
    }

    private static List<ProviderRecord> records(List<WorldItinerary> world) {
        return world.stream().flatMap(i -> i.records().stream()).toList();
    }

    private static FeedCurrency feedCurrency(Currency currency) {
        return MIXED.stream()
                .filter(c -> c.currency().equals(currency))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("Spec 008 AC-12: every record declares one of its provider's currencies, each about a third")
    void currenciesAreMixed() {
        for (ProviderId provider : PROVIDERS) {
            List<ProviderRecord> providerRecords = records(mixedWorld).stream()
                    .filter(r -> r.provider().equals(provider))
                    .toList();
            Map<Currency, Long> counts = providerRecords.stream()
                    .collect(Collectors.groupingBy(ProviderRecord::currency, Collectors.counting()));

            assertThat(counts).as(provider.value()).containsOnlyKeys(EUR.currency(), USD.currency(), GBP.currency());
            counts.values()
                    .forEach(count -> assertThat((double) count / providerRecords.size())
                            .as("%s share of %s", provider.value(), counts)
                            .isBetween(0.2333, 0.4333));
        }
    }

    @Test
    @DisplayName("Spec 008 AC-12: the price is the record's EUR price × the rate, HALF_EVEN; malformed prices stay "
            + "non-positive")
    void priceIsConverted() {
        Map<String, ProviderRecord> eurById = records(eurWorld).stream()
                .collect(Collectors.toMap(r -> r.provider().value() + r.recordId(), Function.identity()));

        for (ProviderRecord record : records(mixedWorld)) {
            ProviderRecord inEur = eurById.get(record.provider().value() + record.recordId());
            assertThat(inEur.currency()).isEqualTo(EUR.currency());
            assertThat(record.price())
                    .as(record.recordId())
                    .isEqualTo(feedCurrency(record.currency()).fromEur(inEur.price()));
            if (inEur.price().signum() <= 0) {
                assertThat(record.price().signum()).isLessThanOrEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("Spec 008 AC-12: the same seed always gives the same currency to the same record")
    void deterministic() {
        assertThat(mixedGenerator().itineraries().toList()).isEqualTo(mixedWorld);
        assertThat(mixedGenerator()
                        .records(PROVIDERS.get(1), mixedWorld.getFirst().partitionDay())
                        .toList())
                .isEqualTo(mixedWorld.stream()
                        .filter(i ->
                                i.partitionDay().equals(mixedWorld.getFirst().partitionDay()))
                        .flatMap(i -> i.records().stream())
                        .filter(r -> r.provider().equals(PROVIDERS.get(1)))
                        .toList());
    }

    @Test
    @DisplayName("Spec 008 AC-12: another seed gives other currencies")
    void seedChangesTheDraw() {
        List<Currency> seed7 =
                records(mixedWorld).stream().map(ProviderRecord::currency).toList();
        List<Currency> seed8 = new WorldGenerator(
                        settings(8L, VOLUME, 0.05, 0.01, 0.005),
                        PROVIDERS,
                        ProviderCurrencies.of(PROVIDERS.stream().collect(Collectors.toMap(p -> p, p -> MIXED))),
                        CLOCK)
                .itineraries()
                .flatMap(i -> i.records().stream())
                .map(ProviderRecord::currency)
                .toList();

        assertThat(seed8).isNotEqualTo(seed7);
    }

    @Test
    @DisplayName("Spec 008 AC-13: the currency draw does not change the world: itineraries, records and stats")
    void worldUnchanged() {
        assertThat(mixedWorld).hasSameSizeAs(eurWorld);
        for (int i = 0; i < eurWorld.size(); i++) {
            WorldItinerary eur = eurWorld.get(i);
            WorldItinerary mixed = mixedWorld.get(i);
            assertThat(mixed.segments()).isEqualTo(eur.segments());
            assertThat(mixed.baseFare()).isEqualTo(eur.baseFare());
            assertThat(mixed.invalidKind()).isEqualTo(eur.invalidKind());
            assertThat(mixed.records())
                    .extracting(
                            ProviderRecord::provider,
                            ProviderRecord::recordId,
                            ProviderRecord::copy,
                            ProviderRecord::segments,
                            ProviderRecord::malformation)
                    .isEqualTo(eur.records().stream()
                            .map(r -> org.assertj.core.groups.Tuple.tuple(
                                    r.provider(), r.recordId(), r.copy(), r.segments(), r.malformation()))
                            .toList());
        }
        WorldStatisticsCalculator calculator = new WorldStatisticsCalculator(PROVIDERS);
        assertThat(calculator.calculate(mixedWorld.stream())).isEqualTo(calculator.calculate(eurWorld.stream()));
    }

    @Test
    @DisplayName("Spec 008 AC-13: a provider configured [EUR] (the default) gets exactly the records it had before")
    void eurOnlyProviderUnchanged() {
        ProviderId alpha = PROVIDERS.getFirst();
        WorldGenerator alphaInEur = new WorldGenerator(
                settings(VOLUME),
                PROVIDERS,
                ProviderCurrencies.of(Map.of(alpha, List.of(EUR), PROVIDERS.get(1), MIXED)),
                CLOCK);

        assertThat(alphaInEur.records(alpha).toList())
                .isEqualTo(generator(settings(VOLUME)).records(alpha).toList());
        assertThat(ProviderCurrencies.eurOnly().currencies(alpha)).containsExactly(EUR);
    }

    @Test
    @DisplayName("Spec 008 AC-14: a provider's currency list must not be empty")
    void emptyListRejected() {
        assertThatThrownBy(() -> ProviderCurrencies.of(Map.of(PROVIDERS.getFirst(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mock-alpha");
    }
}
