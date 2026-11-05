package com.andresps.flights.ingestion.application.service;

import com.andresps.flights.aggregation.domain.deduplication.AggregationResult;
import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.deduplication.OfferAggregator;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.pricing.MarkupPolicy;
import com.andresps.flights.aggregation.domain.validation.ItineraryValidator;
import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.application.port.out.DayAggregationSink;
import com.andresps.flights.ingestion.application.port.out.PartitionFetchException;
import com.andresps.flights.ingestion.application.port.out.ProviderFeed;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.DayAggregation;
import com.andresps.flights.ingestion.domain.DayReport;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.domain.PartitionFailure;
import com.andresps.flights.ingestion.domain.ProviderPartition;
import com.andresps.flights.ingestion.domain.ProviderReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingestion use case (spec 003). For each horizon day, a coordinator fetches that day from every provider in parallel
 * and feeds a dedicated {@link OfferAggregator} with each partition as it completes; it is the aggregator's only
 * consumer, so no lock is needed. When the day's partitions are done the day is published and released (ADR-0016).
 * Days run concurrently up to {@code maxConcurrentDays}; each provider has at most {@code maxConcurrentPartitions}
 * partitions in flight. Everything runs on virtual threads.
 *
 * <p>Provider failures never fail the run: they become {@link PartitionFailure}s in the report. When the run deadline
 * expires, unfinished partitions are cancelled (their threads interrupted) and reported as {@code RUN_TIMEOUT}.
 *
 * <p>Must not be invoked concurrently (ADR-0006): the guard belongs to the trigger.
 */
public final class IngestionService implements IngestFlightsUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionService.class);

    private final List<ProviderFeed> feeds;
    private final DayAggregationSink sink;
    private final MarkupPolicy markupPolicy;
    private final Clock clock;
    private final IngestionSettings settings;

    public IngestionService(
            List<ProviderFeed> feeds,
            DayAggregationSink sink,
            MarkupPolicy markupPolicy,
            Clock clock,
            IngestionSettings settings) {
        this.feeds = List.copyOf(feeds);
        this.sink = Objects.requireNonNull(sink, "sink");
        this.markupPolicy = Objects.requireNonNull(markupPolicy, "markupPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (this.feeds.isEmpty()) {
            throw new IllegalArgumentException("At least one provider feed is required");
        }
        var seen = new HashSet<ProviderId>();
        for (ProviderFeed feed : this.feeds) {
            if (!seen.add(feed.provider())) {
                throw new IllegalArgumentException("Duplicate provider feed " + feed.provider());
            }
        }
    }

    @Override
    public IngestionReport ingest() {
        UUID runId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        LocalDate reference = LocalDate.ofInstant(startedAt, ZoneOffset.UTC);
        List<LocalDate> days = IntStream.rangeClosed(1, settings.horizonDays())
                .mapToObj(reference::plusDays)
                .toList();

        // "Now" is read once per run and shared by every day (ADR-0011).
        Run run = new Run(days.size(), ItineraryValidator.withDefaultRules(clock));
        List<DayReport> dayReports = run.execute(days);

        AggregationStatistics statistics = dayReports.stream()
                .map(DayReport::statistics)
                .reduce(AggregationStatistics.empty(), AggregationStatistics::plus);
        IngestionReport report =
                new IngestionReport(runId, startedAt, clock.instant(), run.providerReports(), dayReports, statistics);
        log(report);
        return report;
    }

    private static void log(IngestionReport report) {
        LOG.info(
                "Ingestion {} {}: {} itineraries from {} offers ({} discarded, {} duplicates) in {} ms; providers {}",
                report.runId(),
                report.status(),
                report.statistics().itinerariesProduced(),
                report.statistics().totalReceived(),
                report.statistics().totalDiscarded(),
                report.statistics().duplicatesMerged(),
                report.duration().toMillis(),
                report.providers().stream()
                        .map(p -> p.provider() + "=" + p.status() + "(" + p.partitionsSucceeded() + "/"
                                + p.partitionsRequested() + ", " + p.circuitState() + ")")
                        .toList());
    }

    /** State of one run: deadline, concurrency limits and per-provider tallies. */
    private final class Run {

        private final int partitionsPerProvider;
        private final ItineraryValidator validator;
        private final long deadline;
        private final Semaphore dayPermits;
        private final Map<ProviderId, Semaphore> providerPermits = new LinkedHashMap<>();
        private final Map<ProviderId, ProviderTally> tallies = new LinkedHashMap<>();
        private ExecutorService executor;

        Run(int partitionsPerProvider, ItineraryValidator validator) {
            this.partitionsPerProvider = partitionsPerProvider;
            this.validator = validator;
            this.deadline = System.nanoTime() + settings.runTimeout().toNanos();
            // fair (FIFO), so days start roughly in horizon order and the nearest days are the likeliest to beat the
            // deadline
            this.dayPermits = new Semaphore(settings.maxConcurrentDays(), true);
            for (ProviderFeed feed : feeds) {
                providerPermits.put(feed.provider(), new Semaphore(settings.maxConcurrentPartitions()));
                tallies.put(feed.provider(), new ProviderTally());
            }
        }

        List<DayReport> execute(List<LocalDate> days) {
            // close() waits for every task, including cancelled ones: no request outlives the run.
            try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
                this.executor = virtualThreads;
                List<Future<DayReport>> coordinators = days.stream()
                        .map(day -> virtualThreads.submit(() -> runDay(day)))
                        .toList();
                return coordinators.stream().map(Run::join).toList();
            }
        }

        List<ProviderReport> providerReports() {
            return feeds.stream()
                    .map(feed -> tallies.get(feed.provider())
                            .report(feed.provider(), partitionsPerProvider, feed.circuitState()))
                    .toList();
        }

        private static DayReport join(Future<DayReport> coordinator) {
            try {
                return coordinator.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the ingestion to finish", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("A day of the ingestion failed unexpectedly", e.getCause());
            }
        }

        private long remainingNanos() {
            return Math.max(0, deadline - System.nanoTime());
        }

        private DayReport runDay(LocalDate day) throws InterruptedException {
            if (!dayPermits.tryAcquire(remainingNanos(), TimeUnit.NANOSECONDS)) {
                return dayNotStarted(day);
            }
            try {
                return aggregateDay(day);
            } finally {
                dayPermits.release();
            }
        }

        private DayReport dayNotStarted(LocalDate day) {
            SortedSet<ProviderId> missing = new TreeSet<>();
            for (ProviderFeed feed : feeds) {
                tallies.get(feed.provider()).record(PartitionOutcome.runTimeout(feed.provider(), day));
                missing.add(feed.provider());
            }
            return new DayReport(day, new TreeSet<>(), missing, false, AggregationStatistics.empty());
        }

        private DayReport aggregateDay(LocalDate day) throws InterruptedException {
            BlockingQueue<PartitionOutcome> completed = new LinkedBlockingQueue<>();
            Map<ProviderId, Future<?>> inFlight = new LinkedHashMap<>();
            for (ProviderFeed feed : feeds) {
                inFlight.put(feed.provider(), executor.submit(() -> fetchInto(completed, feed, day)));
            }

            OfferAggregator aggregator = new OfferAggregator(validator, markupPolicy);
            SortedSet<ProviderId> contributing = new TreeSet<>();
            SortedSet<ProviderId> missing = new TreeSet<>();
            while (!inFlight.isEmpty()) {
                PartitionOutcome outcome = completed.poll(remainingNanos(), TimeUnit.NANOSECONDS);
                if (outcome == null) {
                    // Outcomes delivered right at the deadline still count; only what is still running is cancelled.
                    List<PartitionOutcome> delivered = new ArrayList<>();
                    completed.drainTo(delivered);
                    delivered.forEach(late -> consume(late, inFlight, aggregator, contributing, missing));
                    cancel(day, inFlight, missing);
                    break;
                }
                consume(outcome, inFlight, aggregator, contributing, missing);
            }

            AggregationResult result = aggregator.result();
            boolean published = !contributing.isEmpty() && publish(day, result, contributing, missing);
            return new DayReport(day, contributing, missing, published, result.statistics());
        }

        private void consume(
                PartitionOutcome outcome,
                Map<ProviderId, Future<?>> inFlight,
                OfferAggregator aggregator,
                SortedSet<ProviderId> contributing,
                SortedSet<ProviderId> missing) {
            inFlight.remove(outcome.provider());
            tallies.get(outcome.provider()).record(outcome);
            if (outcome.partition() != null) {
                // Single consumer: only this coordinator touches the day's aggregator. The partition's buffer
                // becomes garbage as soon as it has been fed.
                outcome.partition().offers().forEach(aggregator::accept);
                contributing.add(outcome.provider());
            } else {
                missing.add(outcome.provider());
            }
        }

        /** A sink failure loses that day's publication, not the run: the day is reported as not published. */
        private boolean publish(
                LocalDate day,
                AggregationResult result,
                SortedSet<ProviderId> contributing,
                SortedSet<ProviderId> missing) {
            try {
                sink.publish(new DayAggregation(day, result, contributing, missing));
                return true;
            } catch (RuntimeException e) {
                LOG.error("Publishing {} failed; the day is reported as not published", day, e);
                return false;
            }
        }

        /** Run deadline: interrupt what is still running for the day and report it as RUN_TIMEOUT. */
        private void cancel(LocalDate day, Map<ProviderId, Future<?>> inFlight, SortedSet<ProviderId> missing) {
            inFlight.forEach((provider, future) -> {
                future.cancel(true);
                tallies.get(provider).record(PartitionOutcome.runTimeout(provider, day));
                missing.add(provider);
            });
            LOG.warn("Run deadline reached: {} partitions of {} cancelled {}", inFlight.size(), day, inFlight.keySet());
        }

        /**
         * Always delivers an outcome to the day's coordinator, even when the fetch dies with an {@link Error} (e.g.
         * {@code OutOfMemoryError}): otherwise the coordinator would wait for the run deadline. The error is reported
         * as {@code UNEXPECTED_ERROR} and then rethrown, never swallowed.
         */
        private void fetchInto(BlockingQueue<PartitionOutcome> completed, ProviderFeed feed, LocalDate day) {
            try {
                completed.add(fetch(feed, day));
            } catch (Error e) {
                LOG.error("Partition {} {} died with {}", feed.provider(), day, e.toString());
                completed.add(PartitionOutcome.failed(
                        feed.provider(),
                        new PartitionFailure(day, FailureKind.UNEXPECTED_ERROR, 0, e.toString()),
                        -1,
                        -1));
                throw e;
            }
        }

        private PartitionOutcome fetch(ProviderFeed feed, LocalDate day) {
            Semaphore permits = providerPermits.get(feed.provider());
            try {
                // A partition still waiting for its provider at the deadline never starts a request.
                if (!permits.tryAcquire(remainingNanos(), TimeUnit.NANOSECONDS)) {
                    return PartitionOutcome.runTimeout(feed.provider(), day);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return PartitionOutcome.runTimeout(feed.provider(), day);
            }
            long start = System.nanoTime();
            try {
                if (remainingNanos() == 0) {
                    return PartitionOutcome.runTimeout(feed.provider(), day);
                }
                ProviderPartition partition = feed.fetch(day);
                return PartitionOutcome.succeeded(partition, start, System.nanoTime());
            } catch (PartitionFetchException e) {
                LOG.warn("Partition {} {} failed: {} after {} attempts", feed.provider(), day, e.kind(), e.attempts());
                return PartitionOutcome.failed(
                        feed.provider(),
                        new PartitionFailure(day, e.kind(), e.attempts(), e.getMessage()),
                        start,
                        System.nanoTime());
            } catch (RuntimeException e) {
                LOG.error("Partition {} {} failed with an unexpected error", feed.provider(), day, e);
                return PartitionOutcome.failed(
                        feed.provider(),
                        new PartitionFailure(day, FailureKind.UNEXPECTED_ERROR, 0, e.toString()),
                        start,
                        System.nanoTime());
            } finally {
                permits.release();
            }
        }
    }

    /** The end of one partition: a parsed partition or a failure, with its timing ({@code -1} when it never ran). */
    private record PartitionOutcome(
            ProviderId provider, ProviderPartition partition, PartitionFailure failure, long start, long end) {

        static PartitionOutcome succeeded(ProviderPartition partition, long start, long end) {
            return new PartitionOutcome(partition.provider(), partition, null, start, end);
        }

        static PartitionOutcome failed(ProviderId provider, PartitionFailure failure, long start, long end) {
            return new PartitionOutcome(provider, null, failure, start, end);
        }

        static PartitionOutcome runTimeout(ProviderId provider, LocalDate day) {
            PartitionFailure failure = new PartitionFailure(
                    day, FailureKind.RUN_TIMEOUT, 0, "run deadline reached before the partition finished");
            return new PartitionOutcome(provider, null, failure, -1, -1);
        }
    }

    /** Per-provider counters, updated by several day coordinators. */
    private static final class ProviderTally {

        private int succeeded;
        private final List<PartitionFailure> failures = new ArrayList<>();
        private int attempts;
        private long offers;
        private final Map<MalformedReason, Long> malformed = new EnumMap<>(MalformedReason.class);
        private long firstStart = Long.MAX_VALUE;
        private long lastEnd = Long.MIN_VALUE;

        synchronized void record(PartitionOutcome outcome) {
            if (outcome.start() >= 0) {
                firstStart = Math.min(firstStart, outcome.start());
                lastEnd = Math.max(lastEnd, outcome.end());
            }
            if (outcome.partition() != null) {
                ProviderPartition partition = outcome.partition();
                succeeded++;
                attempts += partition.attempts();
                offers += partition.offers().size();
                partition.malformed().forEach((reason, count) -> malformed.merge(reason, count, Long::sum));
            } else {
                failures.add(outcome.failure());
                attempts += outcome.failure().attempts();
            }
        }

        synchronized ProviderReport report(ProviderId provider, int requested, CircuitState circuitState) {
            Duration duration = firstStart <= lastEnd ? Duration.ofNanos(lastEnd - firstStart) : Duration.ZERO;
            List<PartitionFailure> byDay = failures.stream()
                    .sorted(Comparator.comparing(PartitionFailure::day))
                    .toList();
            return new ProviderReport(
                    provider, requested, succeeded, byDay, attempts, duration, circuitState, offers, malformed);
        }
    }
}
