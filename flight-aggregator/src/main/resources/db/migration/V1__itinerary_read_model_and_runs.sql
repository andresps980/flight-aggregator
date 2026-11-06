-- Spec 004: the itinerary read model for search, day snapshot status and ingestion runs.

-- One denormalized row per aggregated itinerary: every search filter and the keyset sort key (final_price, id) are
-- scalar columns of the same row, and segments travel as JSONB, so a search page is one index range scan with no
-- join. Search indexes are added (and measured against the bulk load) by the search spec.
CREATE TABLE itinerary (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- keyset tie-breaker (ADR-0008)
    itinerary_key     text          NOT NULL,                           -- spec 001 ItineraryKey
    departure_date    date          NOT NULL,                           -- UTC date of the first departure = the day
    origin            char(3)       NOT NULL,
    destination       char(3)       NOT NULL,
    departure_at      timestamptz   NOT NULL,                           -- first departure
    arrival_at        timestamptz   NOT NULL,                           -- last arrival
    airline           char(2)       NOT NULL,
    stops             smallint      NOT NULL CHECK (stops >= 0),
    base_price        numeric(12,2) NOT NULL CHECK (base_price >= 0),   -- EUR (ADR-0010)
    final_price       numeric(12,2) NOT NULL CHECK (final_price >= base_price),
    selected_provider text          NOT NULL,
    providers         text[]        NOT NULL,
    segments          jsonb         NOT NULL,
    run_id            uuid          NOT NULL,
    created_at        timestamptz   NOT NULL,
    CONSTRAINT itinerary_key_unique UNIQUE (itinerary_key)
);

-- A day's replacement and the retention are one indexed DELETE each (ADR-0017).
CREATE INDEX itinerary_departure_date_idx ON itinerary (departure_date);

-- One row per day that has a snapshot (ADR-0017).
CREATE TABLE day_snapshot (
    departure_date    date        PRIMARY KEY,
    run_id            uuid        NOT NULL,     -- run that wrote the snapshot
    published_at      timestamptz NOT NULL,
    providers         text[]      NOT NULL,     -- providers the snapshot came from
    missing_providers text[]      NOT NULL,
    complete          boolean     NOT NULL,
    itineraries       integer     NOT NULL CHECK (itineraries >= 0),
    stale_since       timestamptz,              -- first run that could not refresh it; null when fresh
    last_run_id       uuid        NOT NULL      -- latest run that covered the day
);

CREATE TABLE ingestion_run (
    id          uuid        PRIMARY KEY,
    trigger     text        NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    status      text        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'ABORTED')),
    started_at  timestamptz NOT NULL,
    finished_at timestamptz,
    report      jsonb,                          -- spec 003 report + per-day outcome, written when the run finishes
    failure     text                            -- error of a FAILED / lock-lost / ABORTED run
);

CREATE INDEX ingestion_run_started_at_idx ON ingestion_run (started_at DESC);
