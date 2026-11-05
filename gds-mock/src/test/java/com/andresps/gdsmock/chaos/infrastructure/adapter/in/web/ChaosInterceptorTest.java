package com.andresps.gdsmock.chaos.infrastructure.adapter.in.web;

import static com.andresps.gdsmock.support.WorldFixtures.ALPHA;
import static com.andresps.gdsmock.support.WorldFixtures.PROVIDERS;
import static com.andresps.gdsmock.support.WorldFixtures.REFERENCE_DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.andresps.gdsmock.chaos.domain.ChaosPolicy;
import com.andresps.gdsmock.chaos.domain.ChaosSettings;
import com.andresps.gdsmock.chaos.domain.OutageRegistry;
import com.andresps.gdsmock.inventory.domain.model.Horizon;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.FeedRequestResolver;
import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class ChaosInterceptorTest {

    private static final ChaosSettings SETTINGS =
            new ChaosSettings(Duration.ofMillis(100), Duration.ofMillis(200), 0.5);

    private final RandomGenerator random = mock(RandomGenerator.class);
    private final OutageRegistry outages = new OutageRegistry();
    private final List<Duration> sleeps = new ArrayList<>();
    private final ChaosInterceptor interceptor = new ChaosInterceptor(
            new ChaosPolicy(Map.of(ALPHA, SETTINGS), outages, () -> random),
            sleeps::add,
            new FeedRequestResolver(new LinkedHashSet<>(PROVIDERS), () -> Horizon.after(REFERENCE_DATE, 7)));

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("AC-15: a request that proceeds waits the drawn latency, then reaches the controller")
    void proceedsAfterLatency() throws Exception {
        when(random.nextLong(101)).thenReturn(30L);
        when(random.nextDouble()).thenReturn(0.9);

        boolean proceed = interceptor.preHandle(request("mock-alpha", null), new MockHttpServletResponse(), null);

        assertThat(proceed).isTrue();
        assertThat(sleeps).containsExactly(Duration.ofMillis(130));
    }

    @Test
    @DisplayName("AC-16: an injected error waits the latency, then fails with a provider error")
    void failsAfterLatency() {
        when(random.nextLong(101)).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1);

        assertThatThrownBy(
                        () -> interceptor.preHandle(request("mock-alpha", null), new MockHttpServletResponse(), null))
                .isInstanceOf(InjectedProviderErrorException.class)
                .hasMessageContaining("mock-alpha");
        assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("AC-17: a provider in outage fails immediately with an outage error")
    void outageFailsImmediately() {
        outages.start(ALPHA);

        assertThatThrownBy(
                        () -> interceptor.preHandle(request("mock-alpha", null), new MockHttpServletResponse(), null))
                .isInstanceOf(ProviderOutageException.class)
                .hasMessageContaining("mock-alpha");
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("AC-14: unknown providers and bad dates pass through untouched, so the controller answers 404/400")
    void invalidRequestsSkipChaos() throws Exception {
        outages.start(ALPHA);

        assertThat(interceptor.preHandle(request("mock-delta", null), new MockHttpServletResponse(), null))
                .isTrue();
        assertThat(interceptor.preHandle(request("mock-alpha", "2026-13-01"), new MockHttpServletResponse(), null))
                .isTrue();
        assertThat(interceptor.preHandle(request("mock-alpha", "2026-11-05"), new MockHttpServletResponse(), null))
                .isTrue();
        assertThat(sleeps).isEmpty();
        verifyNoInteractions(random);
    }

    @Test
    @DisplayName("AC-15, AC-16: the async re-dispatch after a streamed feed gets no second latency or error")
    void asyncDispatchSkipsChaos() throws Exception {
        outages.start(ALPHA);
        var asyncDispatch = request("mock-alpha", null);
        asyncDispatch.setDispatcherType(DispatcherType.ASYNC);

        assertThat(interceptor.preHandle(asyncDispatch, new MockHttpServletResponse(), null))
                .isTrue();
        assertThat(sleeps).isEmpty();
        verifyNoInteractions(random);
    }

    @Test
    @DisplayName("AC-15: an interrupted wait keeps the interrupt flag and fails the request")
    void interruptedSleep() {
        when(random.nextLong(101)).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9);
        var interrupting = new ChaosInterceptor(
                new ChaosPolicy(Map.of(ALPHA, SETTINGS), outages, () -> random),
                duration -> {
                    throw new InterruptedException("stop");
                },
                new FeedRequestResolver(new LinkedHashSet<>(PROVIDERS), () -> Horizon.after(REFERENCE_DATE, 7)));

        assertThatThrownBy(
                        () -> interrupting.preHandle(request("mock-alpha", null), new MockHttpServletResponse(), null))
                .isInstanceOf(InjectedProviderErrorException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private static MockHttpServletRequest request(String providerId, String date) {
        var request = new MockHttpServletRequest("GET", "/" + providerId + "/flights");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("providerId", providerId));
        if (date != null) {
            request.setParameter("date", date);
        }
        return request;
    }
}
