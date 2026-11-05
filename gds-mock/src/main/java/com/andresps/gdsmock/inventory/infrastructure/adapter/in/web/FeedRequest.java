package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.shared.domain.ProviderId;
import java.time.LocalDate;
import java.util.Optional;

/** A validated feed request: the provider and, optionally, one partition day. */
public record FeedRequest(ProviderId provider, Optional<LocalDate> date) {}
