package com.andresps.gdsmock.inventory.domain.model;

import java.time.ZoneId;
import java.util.Objects;

/**
 * An airport as a provider sends it. The code is not validated here: malformed records carry corrupted codes on
 * purpose (spec 002, AC-7).
 *
 * @param code IATA code (3 letters in well-formed records)
 * @param zone the airport's time zone, used by providers that send local or offset times
 */
public record Airport(String code, ZoneId zone) {

    public Airport {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(zone, "zone");
    }
}
