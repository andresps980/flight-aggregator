package com.andresps.flights.ingestion.infrastructure.json;

import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.validation.DiscardReason;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.DayOutcome;
import com.andresps.flights.ingestion.domain.DayPublication;
import com.andresps.flights.ingestion.domain.DayReport;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.domain.PartitionFailure;
import com.andresps.flights.ingestion.domain.ProviderReport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON form of an {@link IngestionReport} (spec 004): stored in {@code ingestion_run.report} and returned by the
 * REST API, so the database and the API show the same document. Instants and durations are ISO-8601 (exact); derived
 * figures (statuses, totals, {@code durationMs}) are included for readers and ignored when reading back. The
 * {@code @Schema} annotations only document the enumerated values in the OpenAPI description (spec 006).
 */
public final class IngestionReportJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private IngestionReportJson() {}

    public static String write(IngestionReport report) {
        return JSON.writeValueAsString(ReportDocument.of(report));
    }

    public static IngestionReport read(String json) {
        return JSON.readValue(json, ReportDocument.class).toDomain();
    }

    @Schema(description = "Ingestion report of a finished run (spec 003, spec 004)")
    public record ReportDocument(
            String runId,

            @Schema(allowableValues = {"COMPLETED", "PARTIAL", "FAILED"})
            String status,

            String startedAt,
            String finishedAt,
            String duration,
            long durationMs,
            long storedItineraries,
            long misfiledItineraries,
            List<ProviderDocument> providers,
            List<DayDocument> days,
            StatisticsDocument statistics) {

        public static ReportDocument of(IngestionReport report) {
            return new ReportDocument(
                    report.runId().toString(),
                    report.status().name(),
                    report.startedAt().toString(),
                    report.finishedAt().toString(),
                    report.duration().toString(),
                    report.duration().toMillis(),
                    report.storedItineraries(),
                    report.misfiledItineraries(),
                    report.providers().stream().map(ProviderDocument::of).toList(),
                    report.days().stream().map(DayDocument::of).toList(),
                    StatisticsDocument.of(report.statistics()));
        }

        IngestionReport toDomain() {
            return new IngestionReport(
                    UUID.fromString(runId),
                    Instant.parse(startedAt),
                    Instant.parse(finishedAt),
                    providers.stream().map(ProviderDocument::toDomain).toList(),
                    days.stream().map(DayDocument::toDomain).toList(),
                    statistics.toDomain());
        }
    }

    @Schema(description = "What one provider delivered in the run")
    public record ProviderDocument(
            String provider,

            @Schema(allowableValues = {"COMPLETED", "PARTIAL", "FAILED"})
            String status,

            int partitionsRequested,
            int partitionsSucceeded,
            int partitionsFailed,
            List<FailureDocument> failures,
            int attempts,
            String duration,
            long durationMs,

            @Schema(
                    description = "State of the provider's circuit breaker at the end of the run",
                    allowableValues = {"CLOSED", "OPEN", "HALF_OPEN"})
            String circuitState,

            long recordsReceived,
            long offersReceived,
            SortedMap<String, Long> malformed) {

        static ProviderDocument of(ProviderReport report) {
            return new ProviderDocument(
                    report.provider().value(),
                    report.status().name(),
                    report.partitionsRequested(),
                    report.partitionsSucceeded(),
                    report.partitionsFailed(),
                    report.failures().stream().map(FailureDocument::of).toList(),
                    report.attempts(),
                    report.duration().toString(),
                    report.duration().toMillis(),
                    report.circuitState().name(),
                    report.recordsReceived(),
                    report.offersReceived(),
                    byName(report.malformed(), MalformedReason::name));
        }

        ProviderReport toDomain() {
            return new ProviderReport(
                    new ProviderId(provider),
                    partitionsRequested,
                    partitionsSucceeded,
                    failures.stream().map(FailureDocument::toDomain).toList(),
                    attempts,
                    Duration.parse(duration),
                    CircuitState.valueOf(circuitState),
                    offersReceived,
                    toEnumMap(malformed, MalformedReason.class));
        }
    }

    @Schema(description = "A partition (provider, day) that could not be fetched")
    public record FailureDocument(
            String day,

            @Schema(
                    allowableValues = {
                        "TIMEOUT",
                        "SERVER_ERROR",
                        "CLIENT_ERROR",
                        "IO_ERROR",
                        "MALFORMED_DOCUMENT",
                        "CIRCUIT_OPEN",
                        "RUN_TIMEOUT",
                        "UNEXPECTED_ERROR"
                    })
            String kind,

            int attempts,
            String detail) {

        static FailureDocument of(PartitionFailure failure) {
            return new FailureDocument(
                    failure.day().toString(), failure.kind().name(), failure.attempts(), failure.detail());
        }

        PartitionFailure toDomain() {
            return new PartitionFailure(LocalDate.parse(day), FailureKind.valueOf(kind), attempts, detail);
        }
    }

    @Schema(description = "What the run did with one horizon day")
    public record DayDocument(
            String day,
            List<String> contributing,
            List<String> missing,

            @Schema(allowableValues = {"REPLACED", "PUBLISHED_INCOMPLETE", "KEPT_STALE", "NO_DATA", "WRITE_FAILED"})
            String outcome,

            boolean published,
            int storedItineraries,
            int misfiledItineraries,
            StatisticsDocument statistics) {

        static DayDocument of(DayReport report) {
            return new DayDocument(
                    report.day().toString(),
                    ids(report.contributing()),
                    ids(report.missing()),
                    report.publication().outcome().name(),
                    report.published(),
                    report.publication().storedItineraries(),
                    report.publication().misfiledItineraries(),
                    StatisticsDocument.of(report.statistics()));
        }

        DayReport toDomain() {
            return new DayReport(
                    LocalDate.parse(day),
                    providers(contributing),
                    providers(missing),
                    new DayPublication(DayOutcome.valueOf(outcome), storedItineraries, misfiledItineraries),
                    statistics.toDomain());
        }
    }

    public record StatisticsDocument(
            SortedMap<String, Long> offersReceivedByProvider,
            SortedMap<String, Long> discardedByReason,
            long duplicatesMerged,
            long itinerariesProduced,
            long totalReceived,
            long totalDiscarded) {

        static StatisticsDocument of(AggregationStatistics statistics) {
            return new StatisticsDocument(
                    byName(statistics.offersReceivedByProvider(), ProviderId::value),
                    byName(statistics.discardedByReason(), DiscardReason::name),
                    statistics.duplicatesMerged(),
                    statistics.itinerariesProduced(),
                    statistics.totalReceived(),
                    statistics.totalDiscarded());
        }

        AggregationStatistics toDomain() {
            Map<ProviderId, Long> received = new HashMap<>();
            offersReceivedByProvider.forEach((provider, count) -> received.put(new ProviderId(provider), count));
            return new AggregationStatistics(
                    received, toEnumMap(discardedByReason, DiscardReason.class), duplicatesMerged, itinerariesProduced);
        }
    }

    private static <K> SortedMap<String, Long> byName(Map<K, Long> counts, Function<K, String> name) {
        SortedMap<String, Long> sorted = new TreeMap<>();
        counts.forEach((key, count) -> sorted.put(name.apply(key), count));
        return sorted;
    }

    private static <E extends Enum<E>> Map<E, Long> toEnumMap(Map<String, Long> counts, Class<E> type) {
        Map<E, Long> map = new EnumMap<>(type);
        counts.forEach((name, count) -> map.put(Enum.valueOf(type, name), count));
        return map;
    }

    private static List<String> ids(Collection<ProviderId> providers) {
        return providers.stream().map(ProviderId::value).toList();
    }

    private static TreeSet<ProviderId> providers(List<String> ids) {
        return new TreeSet<>(ids.stream().map(ProviderId::new).toList());
    }
}
