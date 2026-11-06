-- Spec 005: search indexes (ADR-0020) and the data version that keys the search cache (ADR-0022).

-- Keyset order for the unfiltered search and any non-selective filter (date, carrier, origin) as a residual;
-- maxPrice becomes the scan's upper bound.
CREATE INDEX itinerary_price_id_idx ON itinerary (final_price, id);

-- A route (origin + destination), with or without date, carrier or maxPrice, in keyset order.
CREATE INDEX itinerary_route_price_id_idx ON itinerary (origin, destination, final_price, id);

-- One row, incremented as the last statement of every transaction that changes itinerary or day_snapshot, so a new
-- version becomes visible exactly with the data it describes.
CREATE TABLE data_version (
    id      boolean PRIMARY KEY DEFAULT true CHECK (id),
    version bigint  NOT NULL
);

INSERT INTO data_version (id, version) VALUES (true, 0);
