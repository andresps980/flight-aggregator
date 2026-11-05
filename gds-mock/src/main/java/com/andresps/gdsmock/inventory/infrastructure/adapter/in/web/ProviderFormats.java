package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The registered provider formats; their ids are the providers the mock serves. */
public final class ProviderFormats {

    private final Map<ProviderId, ProviderFormat> byId;
    private final List<ProviderId> ids;

    public ProviderFormats(List<ProviderFormat> formats) {
        this.byId = formats.stream().collect(Collectors.toMap(ProviderFormat::providerId, Function.identity()));
        this.ids = byId.keySet().stream().sorted(Comparator.naturalOrder()).toList();
    }

    /** Provider ids, sorted. */
    public List<ProviderId> ids() {
        return ids;
    }

    public ProviderFormat get(ProviderId provider) {
        ProviderFormat format = byId.get(provider);
        if (format == null) {
            throw new UnknownProviderException(provider);
        }
        return format;
    }
}
