package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.inventory.domain.generator.WorldGenerator;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /admin/world/stats[?date=]}: the figures the aggregator should reconcile against (spec 002, AC-20). */
@RestController
public class WorldStatsController {

    private final WorldGenerator generator;
    private final WorldStatisticsCache statistics;
    private final FeedRequestResolver resolver;

    public WorldStatsController(
            WorldGenerator generator, WorldStatisticsCache statistics, FeedRequestResolver resolver) {
        this.generator = generator;
        this.statistics = statistics;
        this.resolver = resolver;
    }

    @GetMapping("/admin/world/stats")
    public WorldStatsResponse stats(@RequestParam(required = false) String date) {
        Optional<LocalDate> day = resolver.resolveDate(date);
        return WorldStatsResponse.from(generator.horizon(), day.orElse(null), statistics.get(day));
    }
}
