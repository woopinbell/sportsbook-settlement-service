package com.sportsbook.settlement.domain;

/**
 * settlement-service's own view of a bet's lifecycle. Deliberately narrower than betting-service's
 * {@code BetStatus} (ADR-0013): by the time a bet reaches settlement it is already ACCEPTED, so the
 * only transitions settlement cares about are
 *
 * <ul>
 *   <li>{@code PENDING} — snapshot built from BetPlacedRequested, awaiting its events' results;
 *   <li>{@code SETTLED} — resolved to WON / LOST / PUSH / VOID and (if owed) paid out;
 *   <li>{@code VOIDED} — event cancelled / postponed, stake fully refunded (ADR-0012).
 * </ul>
 *
 * <p>SETTLED and VOIDED are terminal for normal flow; a late correction within the 24h window
 * (ADR-0012) re-opens via admin replay, not an in-place transition.
 */
public enum SettlementStatus {
  PENDING,
  SETTLED,
  VOIDED
}
