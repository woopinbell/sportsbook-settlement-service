-- V4: materialized match results (settlement's own view of event outcomes).
--
-- Persisting the per-selection outcomes a MatchResult delivered lets settlement
-- (a) replay an event on demand (admin-api POST .../replay/{eventId}) and
-- (b) settle a bet whose BetPlacedRequested is consumed after its result arrived
-- (read-model lag). A corrected result within the 24h window (ADR-0012) is an
-- upsert on the same event_id.

CREATE TABLE match_result (
    event_id    UUID                     PRIMARY KEY,
    mode        VARCHAR(16)              NOT NULL,   -- COMPLETED / ABANDONED / VOIDED
    settled_at  TIMESTAMP WITH TIME ZONE NOT NULL,   -- carried from the MatchResult
    received_at TIMESTAMP WITH TIME ZONE NOT NULL    -- when settlement consumed it
);

COMMENT ON TABLE match_result IS 'Settlement''s materialized view of event outcomes; drives replay + late-bet settlement.';

CREATE TABLE match_selection_result (
    event_id     UUID       NOT NULL REFERENCES match_result (event_id),
    selection_id UUID       NOT NULL,
    outcome      VARCHAR(8) NOT NULL,   -- WON / LOST / PUSH / VOID (the resultDetail contract)
    PRIMARY KEY (event_id, selection_id)
);
