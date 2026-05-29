package com.sportsbook.settlement.domain;

/**
 * How an event's result drives per-selection outcomes (mirrors {@code MatchResult.finalStatus}).
 * Lives in {@code domain} so both the persisted match-result record and the service-layer
 * resolution can share it without a package cycle.
 *
 * <ul>
 *   <li>{@code COMPLETED} — outcomes come from the resultDetail contract; a missing selection stays
 *       unresolved (data gap).
 *   <li>{@code ABANDONED} — resolved markets settle from the contract, the rest void.
 *   <li>{@code VOIDED} — every selection on the event voids.
 * </ul>
 */
public enum MatchOutcomeMode {
  COMPLETED,
  ABANDONED,
  VOIDED
}
