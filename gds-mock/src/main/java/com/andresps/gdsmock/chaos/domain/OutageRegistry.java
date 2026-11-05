package com.andresps.gdsmock.chaos.domain;

import com.andresps.gdsmock.shared.domain.ProviderId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Providers in a forced outage, switched at runtime through the admin API (spec 002, AC-17). Thread-safe. */
public final class OutageRegistry {

    private final Set<ProviderId> down = ConcurrentHashMap.newKeySet();

    public void start(ProviderId provider) {
        down.add(provider);
    }

    public void end(ProviderId provider) {
        down.remove(provider);
    }

    public boolean isDown(ProviderId provider) {
        return down.contains(provider);
    }
}
