package com.andresps.gdsmock.chaos.infrastructure.adapter.in.web;

import com.andresps.gdsmock.chaos.domain.ChaosDecision;
import com.andresps.gdsmock.chaos.domain.ChaosPolicy;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.FeedRequest;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.FeedRequestResolver;
import com.andresps.gdsmock.shared.domain.InvalidRequestException;
import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Applies chaos to provider feeds before the controller runs, keeping it out of controllers and serializers.
 * Invalid requests (unknown provider, bad date) pass through untouched so they get their 404/400 without latency.
 *
 * <p>Feeds are streamed asynchronously, so Spring dispatches each request a second time ({@code ASYNC}) once the
 * body is written. Chaos applies to the initial dispatch only: applying it again would double the latency and could
 * inject an error after the feed was already sent.
 */
public class ChaosInterceptor implements HandlerInterceptor {

    private final ChaosPolicy policy;
    private final Sleeper sleeper;
    private final FeedRequestResolver resolver;

    public ChaosInterceptor(ChaosPolicy policy, Sleeper sleeper, FeedRequestResolver resolver) {
        this.policy = policy;
        this.sleeper = sleeper;
        this.resolver = resolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        @SuppressWarnings("unchecked")
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || variables == null
                || variables.get("providerId") == null) {
            return true;
        }
        FeedRequest feed;
        try {
            feed = resolver.resolve(variables.get("providerId"), request.getParameter("date"));
        } catch (UnknownProviderException | InvalidRequestException e) {
            return true;
        }
        ProviderId provider = feed.provider();
        switch (policy.decide(provider)) {
            case ChaosDecision.Outage() -> throw new ProviderOutageException(provider);
            case ChaosDecision.Fail fail -> {
                await(provider, fail.latency());
                throw new InjectedProviderErrorException(provider);
            }
            case ChaosDecision.Proceed proceed -> await(provider, proceed.latency());
        }
        return true;
    }

    private void await(ProviderId provider, Duration latency) {
        if (latency.isZero()) {
            return;
        }
        try {
            sleeper.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InjectedProviderErrorException(provider, e);
        }
    }
}
