package com.andresps.flights.ingestion.application.service;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.BETA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.CLOCK;
import static com.andresps.flights.aggregation.domain.FlightFixtures.GAMMA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.itinerary;
import static com.andresps.flights.aggregation.domain.FlightFixtures.offer;
import static com.andresps.flights.aggregation.domain.FlightFixtures.segment;
import static com.andresps.flights.ingestion.application.service.FakeProviderFeed.awaits;
import static com.andresps.flights.ingestion.application.service.FakeProviderFeed.blocksUntilInterrupted;
import static com.andresps.flights.ingestion.application.service.FakeProviderFeed.fails;
import static com.andresps.flights.ingestion.application.service.FakeProviderFeed.returns;
import static com.andresps.flights.ingestion.application.service.FakeProviderFeed.sleeps;
import static com.andresps.flights.ingestion.application.service.FakeProviderFeed.throwsBug;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.deduplication.OfferAggregator;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Itinerary;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.pricing.PercentageMarkupPolicy;
import com.andresps.flights.aggregation.domain.validation.DiscardReason;
import com.andresps.flights.aggregation.domain.validation.ItineraryValidator;
import com.andresps.flights.ingestion.application.port.out.DayAggregationSink;
import com.andresps.flights.ingestion.application.port.out.ProviderFeed;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.DayAggregation;
import com.andresps.flights.ingestion.domain.DayReport;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionStatus;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.domain.PartitionFailure;
import com.andresps.flights.ingestion.domain.ProviderReport;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The ingestion use case with scripted feeds: day partitioning, parallelism, partial results, report, deadline. */
class IngestionServiceTest {

    private static final LocalDate DAY1 = LocalDate.parse("2026-11-06");
    private static final LocalDate DAY2 = LocalDate.parse("2026-11-07");
    private static final LocalDate DAY3 = LocalDate.parse("2026-11-08");
    private static final List<LocalDate> HORIZON = List.of(DAY1, DAY2, DAY3);

    private static final IngestionSettings SETTINGS = new IngestionSettings(3, 2, 2, Duration.ofSeconds(10));

    private final FakeProviderFeed alpha = new FakeProviderFeed(ALPHA);
    private final FakeProviderFeed beta = new FakeProviderFeed(BETA);
    private final FakeProviderFeed gamma = new FakeProviderFeed(GAMMA);
    private final RecordingSink sink = new RecordingSink();

    private IngestionReport ingest(IngestionSettings settings, ProviderFeed... feeds) {
        return new IngestionService(List.of(feeds), sink, PercentageMarkupPolicy.defaultPolicy(), CLOCK, settings)
                .ingest();
    }

    private IngestionReport ingest() {
        return ingest(SETTINGS, alpha, beta, gamma);
    }

    // ----- fixtures: itineraries departing on a given day (UTC) -----

    private static Itinerary direct(LocalDate day, String flight, String from, String to) {
        return itinerary(segment(flight, from, to, day + "T08:00", day + "T10:00"));
    }

    private static Itinerary mixedAirlines(LocalDate day) {
        return itinerary(
                segment("IB1", "MAD", "BCN", day + "T08:00", day + "T09:00"),
                segment("VY2", "BCN", "LHR", day + "T10:30", day + "T12:30"));
    }

    @Nested
    class Partitioning {

        @Test
        @DisplayName("AC-9: every (provider, day) partition of the horizon is requested exactly once")
        void requestsEveryPartitionOnce() {
            ingest();

            for (FakeProviderFeed feed : List.of(alpha, beta, gamma)) {
                assertThat(HORIZON)
                        .allSatisfy(day -> assertThat(feed.calls(day)).isEqualTo(1));
                assertThat(feed.totalCalls()).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("AC-9: the horizon starts the day after the reference date (today in UTC from the clock)")
        void horizonStartsTomorrow() {
            ingest(new IngestionSettings(1, 1, 1, Duration.ofSeconds(10)), alpha);

            assertThat(alpha.calls(DAY1)).isEqualTo(1);
            assertThat(alpha.totalCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-9: a day's partitions are fetched from all providers at the same time")
        void providersOfADayRunInParallel() {
            CyclicBarrier allThree = new CyclicBarrier(3);
            Stream.of(alpha, beta, gamma).forEach(feed -> feed.on(DAY1, awaits(allThree)));

            IngestionReport report = ingest(new IngestionSettings(1, 1, 1, Duration.ofSeconds(10)), alpha, beta, gamma);

            assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        }

        @Test
        @DisplayName("AC-9: up to max-concurrent-days days run at the same time")
        void daysRunConcurrently() {
            CyclicBarrier threeDays = new CyclicBarrier(3);
            alpha.byDefault(awaits(threeDays));

            IngestionReport report = ingest(new IngestionSettings(3, 3, 3, Duration.ofSeconds(10)), alpha);

            assertThat(report.status()).isEqualTo(IngestionStatus.COMPLETED);
        }

        @Test
        @DisplayName("AC-9: no more than max-concurrent-days days are in flight")
        void dayLimitIsRespected() {
            alpha.byDefault(sleeps(50));

            ingest(new IngestionSettings(3, 1, 3, Duration.ofSeconds(10)), alpha);

            assertThat(alpha.maxInFlight()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-9: no more than max-concurrent-partitions partitions per provider are in flight")
        void providerLimitIsRespected() {
            alpha.byDefault(sleeps(50));
            beta.byDefault(sleeps(50));

            ingest(new IngestionSettings(3, 3, 1, Duration.ofSeconds(10)), alpha, beta);

            assertThat(alpha.maxInFlight()).isEqualTo(1);
            assertThat(beta.maxInFlight()).isEqualTo(1);
        }
    }

    @Nested
    class DayAggregationTests {

        private final FlightOffer a1Alpha = offer(ALPHA, "100.00", direct(DAY1, "IB1001", "MAD", "LHR"));
        private final FlightOffer a1Beta = offer(BETA, "90.00", direct(DAY1, "IB1001", "MAD", "LHR"));
        private final FlightOffer b1Alpha = offer(ALPHA, "80.00", direct(DAY1, "VY2001", "BCN", "ORY"));
        private final FlightOffer b1Gamma = offer(GAMMA, "85.00", direct(DAY1, "VY2001", "BCN", "ORY"));
        private final FlightOffer invalid1Beta = offer(BETA, "50.00", mixedAirlines(DAY1));
        private final FlightOffer c2Alpha = offer(ALPHA, "70.00", direct(DAY2, "UX3001", "MAD", "FCO"));
        private final FlightOffer c2Beta = offer(BETA, "70.00", direct(DAY2, "UX3001", "MAD", "FCO"));

        private void script() {
            alpha.on(DAY1, returns(List.of(a1Alpha, b1Alpha))).on(DAY2, returns(List.of(c2Alpha)));
            beta.on(DAY1, returns(List.of(a1Beta, invalid1Beta))).on(DAY2, returns(List.of(c2Beta)));
            gamma.on(DAY1, returns(List.of(b1Gamma)));
        }

        @Test
        @DisplayName("AC-10: the summed day statistics equal one aggregator fed with every offer")
        void summedStatisticsEqualSingleAggregation() {
            script();
            OfferAggregator single = new OfferAggregator(
                    ItineraryValidator.withDefaultRules(CLOCK), PercentageMarkupPolicy.defaultPolicy());
            Stream.of(a1Alpha, b1Alpha, c2Alpha, a1Beta, invalid1Beta, c2Beta, b1Gamma)
                    .forEach(single::accept);

            IngestionReport report = ingest();

            AggregationStatistics expected = single.result().statistics();
            assertThat(report.statistics()).isEqualTo(expected);
            assertThat(report.statistics().itinerariesProduced()).isEqualTo(3);
            assertThat(report.statistics().duplicatesMerged()).isEqualTo(3);
            assertThat(report.statistics().discarded(DiscardReason.MIXED_AIRLINES))
                    .isEqualTo(1);
            assertThat(report.days().stream()
                            .map(DayReport::statistics)
                            .reduce(AggregationStatistics.empty(), AggregationStatistics::plus))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("AC-10/AC-11: each published day holds only its itineraries, deduplicated, cheapest selected")
        void publishesEachDay() {
            script();

            ingest();

            DayAggregation day1 = sink.day(DAY1);
            assertThat(day1.result().itineraries()).hasSize(2);
            AggregatedItinerary a = byFlight(day1, "IB1001");
            assertThat(a.selectedOffer()).isEqualTo(a1Beta);
            assertThat(a.finalPrice().amount()).isEqualByComparingTo("94.50");
            assertThat(a.providers()).containsExactly(ALPHA, BETA);
            assertThat(byFlight(day1, "VY2001").selectedOffer()).isEqualTo(b1Alpha);

            DayAggregation day2 = sink.day(DAY2);
            assertThat(day2.result().itineraries())
                    .singleElement()
                    .satisfies(i -> assertThat(i.selectedOffer()).isEqualTo(c2Alpha));
            assertThat(sink.day(DAY3).result().itineraries()).isEmpty();
            assertThat(sink.published()).containsExactlyInAnyOrder(DAY1, DAY2, DAY3);
        }

        private static AggregatedItinerary byFlight(DayAggregation day, String flight) {
            return day.result().itineraries().stream()
                    .filter(i -> i.itinerary()
                            .segments()
                            .getFirst()
                            .flightNumber()
                            .toString()
                            .equals(flight))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    class PartialResults {

        @Test
        @DisplayName("AC-11/AC-18: a day missing a provider is published naming it; its failed partition adds nothing")
        void publishesDayWithMissingProvider() {
            alpha.on(DAY2, returns(List.of(offer(ALPHA, "70.00", direct(DAY2, "UX3001", "MAD", "FCO")))));
            beta.on(DAY2, fails(FailureKind.SERVER_ERROR, 3));

            IngestionReport report = ingest();

            DayAggregation day2 = sink.day(DAY2);
            assertThat(day2.contributing()).containsExactly(ALPHA, GAMMA);
            assertThat(day2.missing()).containsExactly(BETA);
            assertThat(day2.result().statistics().received(BETA)).isZero();
            assertThat(report.day(DAY2).published()).isTrue();
            assertThat(report.day(DAY2).missing()).containsExactly(BETA);
        }

        @Test
        @DisplayName("AC-11: a day where every provider failed is not published")
        void dayWithoutAnySuccessIsNotPublished() {
            Stream.of(alpha, beta, gamma).forEach(feed -> feed.on(DAY3, fails(FailureKind.TIMEOUT, 3)));

            IngestionReport report = ingest();

            assertThat(sink.published()).containsExactlyInAnyOrder(DAY1, DAY2);
            assertThat(report.day(DAY3).published()).isFalse();
            assertThat(report.day(DAY3).contributing()).isEmpty();
            assertThat(report.day(DAY3).missing()).containsExactly(ALPHA, BETA, GAMMA);
        }

        @Test
        @DisplayName("AC-19: one failed partition makes its provider PARTIAL and the ingestion PARTIAL")
        void onePartitionFailed() {
            beta.on(DAY2, fails(FailureKind.SERVER_ERROR, 3));

            IngestionReport report = ingest();

            assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
            assertThat(report.provider(BETA).status()).isEqualTo(IngestionStatus.PARTIAL);
            assertThat(report.provider(ALPHA).status()).isEqualTo(IngestionStatus.COMPLETED);
            assertThat(report.provider(GAMMA).status()).isEqualTo(IngestionStatus.COMPLETED);
        }

        @Test
        @DisplayName("AC-19: one provider down makes it FAILED and the ingestion PARTIAL with the other two")
        void oneProviderDown() {
            gamma.byDefault(fails(FailureKind.CIRCUIT_OPEN, 0)).circuitState(CircuitState.OPEN);

            IngestionReport report = ingest();

            assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
            assertThat(report.provider(GAMMA).status()).isEqualTo(IngestionStatus.FAILED);
            assertThat(report.provider(GAMMA).circuitState()).isEqualTo(CircuitState.OPEN);
            assertThat(sink.published()).containsExactlyInAnyOrder(DAY1, DAY2, DAY3);
        }

        @Test
        @DisplayName("AC-19: every partition succeeding makes the ingestion COMPLETED")
        void allSucceeded() {
            assertThat(ingest().status()).isEqualTo(IngestionStatus.COMPLETED);
        }

        @Test
        @DisplayName("AC-20: every provider down returns a FAILED report, publishes nothing and throws nothing")
        void allProvidersDown() {
            Stream.of(alpha, beta, gamma).forEach(feed -> feed.byDefault(fails(FailureKind.SERVER_ERROR, 3)));

            IngestionReport report = ingest();

            assertThat(report.status()).isEqualTo(IngestionStatus.FAILED);
            assertThat(sink.published()).isEmpty();
            assertThat(report.statistics()).isEqualTo(AggregationStatistics.empty());
        }

        @Test
        @DisplayName("AC-21: a bug in an adapter fails only its partition, as UNEXPECTED_ERROR")
        void adapterBugFailsOnlyItsPartition() {
            alpha.on(DAY1, throwsBug());

            IngestionReport report = ingest();

            assertThat(report.provider(ALPHA).failures()).singleElement().satisfies(f -> {
                assertThat(f.day()).isEqualTo(DAY1);
                assertThat(f.kind()).isEqualTo(FailureKind.UNEXPECTED_ERROR);
            });
            assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
        }
    }

    @Nested
    class Report {

        @Test
        @DisplayName(
                "AC-21: per provider, partitions, failures, attempts, circuit state, offers and malformed per reason")
        void providerFigures() {
            Map<MalformedReason, Long> malformedDay1 = Map.of(MalformedReason.BAD_AIRPORT_CODE, 2L);
            Map<MalformedReason, Long> malformedDay2 =
                    Map.of(MalformedReason.BAD_AIRPORT_CODE, 1L, MalformedReason.UNPARSEABLE_TIME, 1L);
            alpha.on(DAY1, returns(List.of(offer(ALPHA, "10.00", direct(DAY1, "IB1", "MAD", "LHR"))), malformedDay1, 2))
                    .on(
                            DAY2,
                            returns(
                                    List.of(
                                            offer(ALPHA, "11.00", direct(DAY2, "IB2", "MAD", "LHR")),
                                            offer(ALPHA, "12.00", direct(DAY2, "IB3", "MAD", "LHR"))),
                                    malformedDay2,
                                    1))
                    .on(DAY3, fails(FailureKind.TIMEOUT, 3))
                    .circuitState(CircuitState.HALF_OPEN);

            ProviderReport report = ingest().provider(ALPHA);

            assertThat(report.partitionsRequested()).isEqualTo(3);
            assertThat(report.partitionsSucceeded()).isEqualTo(2);
            assertThat(report.failures())
                    .containsExactly(new PartitionFailure(DAY3, FailureKind.TIMEOUT, 3, "TIMEOUT (test)"));
            assertThat(report.attempts()).isEqualTo(6);
            assertThat(report.circuitState()).isEqualTo(CircuitState.HALF_OPEN);
            assertThat(report.offersReceived()).isEqualTo(3);
            assertThat(report.malformed())
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of(MalformedReason.BAD_AIRPORT_CODE, 3L, MalformedReason.UNPARSEABLE_TIME, 1L));
            assertThat(report.recordsReceived()).isEqualTo(7);
            assertThat(report.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("AC-21: offers received per provider equal the aggregation's received count for that provider")
        void offersMatchAggregation() {
            alpha.on(DAY1, returns(List.of(offer(ALPHA, "10.00", direct(DAY1, "IB1", "MAD", "LHR")))));
            beta.on(DAY1, returns(List.of(offer(BETA, "10.00", mixedAirlines(DAY1)))));

            IngestionReport report = ingest();

            for (ProviderId provider : List.of(ALPHA, BETA, GAMMA)) {
                assertThat(report.provider(provider).offersReceived())
                        .isEqualTo(report.statistics().received(provider));
            }
        }

        @Test
        @DisplayName("AC-21: the run has an id, start and end instants from the clock, and a report per day")
        void runFigures() {
            IngestionReport report = ingest();

            assertThat(report.runId()).isNotNull();
            assertThat(report.startedAt()).isEqualTo(CLOCK.instant());
            assertThat(report.finishedAt()).isEqualTo(CLOCK.instant());
            assertThat(report.days()).extracting(DayReport::day).containsExactly(DAY1, DAY2, DAY3);
            assertThat(report.providers()).extracting(ProviderReport::provider).containsExactly(ALPHA, BETA, GAMMA);
        }
    }

    @Nested
    class RunDeadline {

        @Test
        @DisplayName(
                "AC-13: at run-timeout unfinished partitions are cancelled as RUN_TIMEOUT and the report is returned")
        void cancelsUnfinishedPartitions() {
            // One permit for Beta: whichever day gets it blocks; the other two wait and must never start a request.
            beta.byDefault(blocksUntilInterrupted());
            IngestionSettings settings = new IngestionSettings(3, 3, 1, Duration.ofMillis(300));
            long start = System.nanoTime();

            IngestionReport report = ingest(settings, alpha, beta, gamma);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(beta.interruptions())
                    .as("the blocked request was interrupted")
                    .isEqualTo(1);
            ProviderReport betaReport = report.provider(BETA);
            assertThat(betaReport.failures())
                    .extracting(PartitionFailure::kind)
                    .containsOnly(FailureKind.RUN_TIMEOUT)
                    .hasSize(3);
            assertThat(betaReport.status()).isEqualTo(IngestionStatus.FAILED);
            assertThat(report.provider(ALPHA).status()).isEqualTo(IngestionStatus.COMPLETED);
            assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
            assertThat(sink.published()).containsExactlyInAnyOrder(DAY1, DAY2, DAY3);
            assertThat(sink.day(DAY1).missing()).containsExactly(BETA);
        }
    }

    @Nested
    class Construction {

        @Test
        @DisplayName("AC-23: at least one feed and unique provider ids are required")
        void validatesFeeds() {
            var markup = PercentageMarkupPolicy.defaultPolicy();

            assertThatThrownBy(() -> new IngestionService(List.of(), sink, markup, CLOCK, SETTINGS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new IngestionService(
                            List.of(alpha, new FakeProviderFeed(ALPHA)), sink, markup, CLOCK, SETTINGS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mock-alpha");
        }

        @Test
        @DisplayName("AC-23: settings must be positive and the horizon within 1..30 days")
        void validatesSettings() {
            assertThatThrownBy(() -> new IngestionSettings(0, 1, 1, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("horizon-days");
            assertThatThrownBy(() -> new IngestionSettings(31, 1, 1, Duration.ofSeconds(1)))
                    .hasMessageContaining("horizon-days");
            assertThatThrownBy(() -> new IngestionSettings(7, 0, 1, Duration.ofSeconds(1)))
                    .hasMessageContaining("max-concurrent-days");
            assertThatThrownBy(() -> new IngestionSettings(7, 1, 0, Duration.ofSeconds(1)))
                    .hasMessageContaining("max-concurrent-partitions");
            assertThatThrownBy(() -> new IngestionSettings(7, 1, 1, Duration.ZERO))
                    .hasMessageContaining("run-timeout");
        }
    }

    /** Thread-safe sink that records what was published. */
    static final class RecordingSink implements DayAggregationSink {

        private final Map<LocalDate, DayAggregation> days = new ConcurrentHashMap<>();

        @Override
        public void publish(DayAggregation day) {
            days.put(day.day(), day);
        }

        DayAggregation day(LocalDate day) {
            return days.get(day);
        }

        Set<LocalDate> published() {
            return days.keySet();
        }
    }
}
