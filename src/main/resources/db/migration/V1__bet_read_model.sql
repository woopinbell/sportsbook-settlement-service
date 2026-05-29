-- V1: settlement-service read model (ADR-0006 event sourcing).
--
-- settlement-service does NOT read the betting-service DB. It rebuilds the bet
-- snapshot it needs from the BetPlacedRequested event (topic bet.placed.v1) and
-- tracks each bet's settlement lifecycle here. A bet row is created PENDING on
-- BetPlacedRequested and transitions to SETTLED / VOIDED once its events resolve
-- (ADR-0013 status: settlement uses PENDING / SETTLED / VOIDED).

CREATE TABLE bet (
    bet_id                  UUID                     PRIMARY KEY,
    user_id                 UUID                     NOT NULL,
    slip_type               VARCHAR(16)              NOT NULL,   -- SINGLE / MULTIPLE / SYSTEM
    system_min_wins         INT,                                 -- K, SYSTEM slips only
    system_total_selections INT,                                 -- N, SYSTEM slips only
    stake_amount            BIGINT                   NOT NULL,   -- per-line (unit) stake, minor units
    stake_currency          VARCHAR(3)               NOT NULL,
    status                  VARCHAR(16)              NOT NULL,   -- PENDING / SETTLED / VOIDED
    result                  VARCHAR(8),                          -- WON / LOST / PUSH / VOID (when SETTLED)
    payout_amount           BIGINT,                              -- credited amount (when terminal)
    payout_currency         VARCHAR(3),
    requested_at            TIMESTAMP WITH TIME ZONE NOT NULL,   -- carried from the event
    settled_at              TIMESTAMP WITH TIME ZONE,            -- terminal transition time
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
);

COMMENT ON TABLE  bet            IS 'Event-sourced bet snapshot + settlement lifecycle. Rebuilt from BetPlacedRequested; never read from betting-service DB.';
COMMENT ON COLUMN bet.slip_type  IS 'BetSlipType tag (ADR-0008). SYSTEM also sets system_min_wins (K) + system_total_selections (N).';
COMMENT ON COLUMN bet.stake_amount IS 'Per-line (unit) stake. Total committed = unit * line count; settlement re-derives line count from slip_type.';
COMMENT ON COLUMN bet.status     IS 'Settlement lifecycle: PENDING (awaiting results) -> SETTLED | VOIDED. Distinct from betting-service BetStatus.';

CREATE TABLE bet_selection (
    selection_row_id UUID         PRIMARY KEY,
    bet_id           UUID         NOT NULL REFERENCES bet (bet_id),
    leg_index        INT          NOT NULL,
    event_id         UUID         NOT NULL,
    market_id        UUID         NOT NULL,
    selection_id     UUID         NOT NULL,
    odds             NUMERIC(9,4) NOT NULL,   -- oddsAtSubmission; V1 uses submission odds for payout
    outcome          VARCHAR(8),              -- per-selection WON / LOST / PUSH / VOID (set at settle time)
    CONSTRAINT uq_bet_selection_order UNIQUE (bet_id, leg_index)
);

COMMENT ON COLUMN bet_selection.odds    IS 'Decimal odds from BetPlacedRequested.oddsAtSubmission (scale 4). V1 has no accepted-odds field; submission odds drive payout.';
COMMENT ON COLUMN bet_selection.outcome IS 'Per-selection result read from MatchResult.resultDetail at settle time. NULL until that selection''s event resolves.';

-- Settlement scans bets by the event that just resolved ("all bets with a
-- selection on event X"), so index the join column.
CREATE INDEX ix_bet_selection_event ON bet_selection (event_id);

-- Find still-open bets quickly (settlement trigger + late-window scans).
CREATE INDEX ix_bet_pending ON bet (status) WHERE status = 'PENDING';
