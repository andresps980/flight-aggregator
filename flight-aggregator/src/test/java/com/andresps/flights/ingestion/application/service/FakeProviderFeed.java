package com.andresps.flights.ingestion.application.service;

import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.application.port.out.PartitionFetchException;
import com.andresps.flights.ingestion.application.port.out.ProviderFeed;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.domain.ProviderPartition;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Scriptable {@link ProviderFeed} for use-case tests. Unscripted days return an empty partition. */
final class FakeProviderFeed implements ProviderFeed {

    @FunctionalInterface
    interface Behaviour {
        ProviderPartition fetch(ProviderId provider, LocalDate day) throws InterruptedException;
    }

    private final ProviderId provider;
    private final Map<LocalDate, Behaviour> script = new ConcurrentHashMap<>();
    private final Map<LocalDate, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger interruptions = new AtomicInteger();
    private final List<ExchangeRates> ratesReceived = new CopyOnWriteArrayList<>();
    private volatile Behaviour defaultBehaviour = (provider, day) -> partition(provider, day, List.of());
    private volatile CircuitState circuitState = CircuitState.CLOSED;

    FakeProviderFeed(ProviderId provider) {
        this.provider = provider;
    }

    FakeProviderFeed on(LocalDate day, Behaviour behaviour) {
        script.put(day, behaviour);
        return this;
    }

    FakeProviderFeed byDefault(Behaviour behaviour) {
        this.defaultBehaviour = behaviour;
        return this;
    }

    FakeProviderFeed circuitState(CircuitState state) {
        this.circuitState = state;
        return this;
    }

    @Override
    public ProviderId provider() {
        return provider;
    }

    @Override
    public ProviderPartition fetch(LocalDate day, ExchangeRates rates) {
        ratesReceived.add(rates);
        calls.computeIfAbsent(day, d -> new AtomicInteger()).incrementAndGet();
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            return script.getOrDefault(day, defaultBehaviour).fetch(provider, day);
        } catch (InterruptedException e) {
            interruptions.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new PartitionFetchException(FailureKind.IO_ERROR, 1, "interrupted", e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Override
    public CircuitState circuitState() {
        return circuitState;
    }

    int calls(LocalDate day) {
        AtomicInteger count = calls.get(day);
        return count == null ? 0 : count.get();
    }

    int totalCalls() {
        return calls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /** The rates passed to every fetch, in call order (spec 008). */
    List<ExchangeRates> ratesReceived() {
        return List.copyOf(ratesReceived);
    }

    int maxInFlight() {
        return maxInFlight.get();
    }

    int interruptions() {
        return interruptions.get();
    }

    // ----- behaviours -----

    static ProviderPartition partition(ProviderId provider, LocalDate day, List<FlightOffer> offers) {
        return new ProviderPartition(provider, day, offers, Map.of(), 1);
    }

    static Behaviour returns(List<FlightOffer> offers, Map<MalformedReason, Long> malformed, int attempts) {
        return (provider, day) -> new ProviderPartition(provider, day, offers, malformed, attempts);
    }

    static Behaviour returns(List<FlightOffer> offers) {
        return returns(offers, Map.of(), 1);
    }

    static Behaviour fails(FailureKind kind, int attempts) {
        return (provider, day) -> {
            throw new PartitionFetchException(kind, attempts, kind + " (test)", null);
        };
    }

    static Behaviour throwsBug() {
        return (provider, day) -> {
            throw new IllegalStateException("adapter bug (test)");
        };
    }

    static Behaviour throwsError() {
        return (provider, day) -> {
            throw new OutOfMemoryError("simulated (test)");
        };
    }

    /** Blocks until the calling thread is interrupted (e.g. by the run deadline). */
    static Behaviour blocksUntilInterrupted() {
        return (provider, day) -> {
            new CountDownLatch(1).await();
            throw new IllegalStateException("unreachable");
        };
    }

    /** Waits at {@code barrier} (all parties must be fetching at once), then returns an empty partition. */
    static Behaviour awaits(CyclicBarrier barrier) {
        return (provider, day) -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new PartitionFetchException(FailureKind.TIMEOUT, 1, "barrier not reached: not parallel", e);
            }
            return partition(provider, day, List.of());
        };
    }

    static Behaviour sleeps(long millis) {
        return (provider, day) -> {
            Thread.sleep(millis);
            return partition(provider, day, List.of());
        };
    }
}
