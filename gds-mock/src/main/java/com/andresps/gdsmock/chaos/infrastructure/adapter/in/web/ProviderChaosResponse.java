package com.andresps.gdsmock.chaos.infrastructure.adapter.in.web;

/** One provider in {@code GET /admin/providers}. */
public record ProviderChaosResponse(
        String id, long latencyMinMs, long latencyMaxMs, double errorRate, boolean outage) {}
