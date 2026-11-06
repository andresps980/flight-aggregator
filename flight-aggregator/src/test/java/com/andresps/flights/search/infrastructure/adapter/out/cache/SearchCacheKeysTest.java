package com.andresps.flights.search.infrastructure.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchCacheKeysTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-06");

    private static SearchCriteria criteria(String o, String d, String date, String max, String c) {
        return SearchCriteria.parse(o, d, date, max, c, TODAY);
    }

    @Test
    @DisplayName("AC-2 / ADR-0022: equivalent requests share one key (normalized filters)")
    void normalized() {
        assertThat(SearchCacheKeys.search(7, criteria("mad", " jfk", null, "100", "ib"), null, 20))
                .isEqualTo(SearchCacheKeys.search(7, criteria("MAD", "JFK", "", "100.00", "IB"), null, 20));
    }

    @Test
    @DisplayName("ADR-0022: the key is versioned and readable")
    void format() {
        String key = SearchCacheKeys.search(
                7,
                criteria("MAD", "JFK", "2026-11-08", "650", "IB"),
                new PagePosition(new BigDecimal("433"), 82134L),
                20);

        assertThat(key)
                .startsWith("search:v1:7:")
                .contains("MAD")
                .contains("JFK")
                .contains("2026-11-08")
                .contains("650.00")
                .contains("IB")
                .contains("433.00")
                .contains("82134");
        assertThat(SearchCacheKeys.search(7, SearchCriteria.none(), null, 20)).endsWith(":first");
        assertThat(SearchCacheKeys.catalog(7)).isEqualTo("catalog:v1:7");
    }

    @Test
    @DisplayName("AC-19 / ADR-0022: version, every filter, the limit and the position each give a different key")
    void distinguishes() {
        PagePosition after = new PagePosition(new BigDecimal("10.00"), 5L);
        List<String> keys = List.of(
                SearchCacheKeys.search(7, SearchCriteria.none(), null, 20),
                SearchCacheKeys.search(8, SearchCriteria.none(), null, 20),
                SearchCacheKeys.search(7, SearchCriteria.none(), null, 21),
                SearchCacheKeys.search(7, SearchCriteria.none(), after, 20),
                SearchCacheKeys.search(7, SearchCriteria.none(), new PagePosition(new BigDecimal("10.00"), 6L), 20),
                SearchCacheKeys.search(7, SearchCriteria.none(), new PagePosition(new BigDecimal("10.01"), 5L), 20),
                SearchCacheKeys.search(7, criteria("MAD", null, null, null, null), null, 20),
                SearchCacheKeys.search(7, criteria(null, "MAD", null, null, null), null, 20),
                SearchCacheKeys.search(7, criteria(null, null, "2026-11-07", null, null), null, 20),
                SearchCacheKeys.search(7, criteria(null, null, null, "99", null), null, 20),
                SearchCacheKeys.search(7, criteria(null, null, null, null, "IB"), null, 20),
                SearchCacheKeys.catalog(7),
                SearchCacheKeys.catalog(8));

        Set<String> distinct = new HashSet<>(keys);
        assertThat(distinct).hasSameSizeAs(keys);
    }
}
