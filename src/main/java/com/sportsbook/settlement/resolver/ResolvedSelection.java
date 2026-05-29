package com.sportsbook.settlement.resolver;

import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Odds;
import java.util.Objects;

/**
 * A single selection's settle-time input: the submission odds (V1 payout basis) plus the
 * per-selection outcome read from {@code MatchResult.resultDetail}. settlement does not re-derive
 * the outcome from the raw score (boundary: it does not decide results) — it is given here and the
 * resolver only does the slip combination maths.
 *
 * <p>{@code outcome} is one of WON / LOST / PUSH / VOID (ADR-0013). half-won / half-lost are out of
 * scope for V1 (ADR-0012), so a selection is always a whole one of those four.
 */
public record ResolvedSelection(Odds odds, SettlementResult outcome) {

  public ResolvedSelection {
    Objects.requireNonNull(odds, "odds");
    Objects.requireNonNull(outcome, "outcome");
  }
}
