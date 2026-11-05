package com.andresps.gdsmock.chaos.infrastructure.adapter.in.web;

import com.andresps.gdsmock.chaos.domain.ChaosPolicy;
import com.andresps.gdsmock.chaos.domain.ChaosSettings;
import com.andresps.gdsmock.chaos.domain.OutageRegistry;
import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API to inspect chaos settings and force provider outages at runtime (spec 002, AC-17). Unauthenticated by
 * design: the mock runs on an internal network.
 */
@RestController
public class AdminProviderController {

    private final ChaosPolicy policy;
    private final OutageRegistry outages;

    public AdminProviderController(ChaosPolicy policy, OutageRegistry outages) {
        this.policy = policy;
        this.outages = outages;
    }

    @GetMapping("/admin/providers")
    public List<ProviderChaosResponse> providers() {
        return policy.providers().stream()
                .map(id -> {
                    ChaosSettings chaos = policy.settings(id);
                    return new ProviderChaosResponse(
                            id.value(),
                            chaos.latencyMin().toMillis(),
                            chaos.latencyMax().toMillis(),
                            chaos.errorRate(),
                            outages.isDown(id));
                })
                .toList();
    }

    @PostMapping("/admin/providers/{providerId}/outage")
    public ResponseEntity<Void> startOutage(@PathVariable String providerId) {
        outages.start(known(providerId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admin/providers/{providerId}/outage")
    public ResponseEntity<Void> endOutage(@PathVariable String providerId) {
        outages.end(known(providerId));
        return ResponseEntity.noContent().build();
    }

    private ProviderId known(String providerId) {
        return policy.providers().stream()
                .filter(id -> id.value().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new UnknownProviderException(providerId));
    }
}
