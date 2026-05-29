package com.sportsbook.settlement.service;

import com.sportsbook.protocol.domain.SettlementResult;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The settle-time decision a MatchResult carries for one event, decoupled from Avro. {@code mode}
 * mirrors {@code MatchResult.finalStatus} and decides how a selection's outcome is derived
 * (ADR-0012 / MatchResult doc):
 *
 * <ul>
 *   <li>{@code COMPLETED} — outcome comes from {@code selectionOutcomes} (the resultDetail
 *       contract); a selection absent from the map stays unresolved (data gap, keeps the bet
 *       PENDING).
 *   <li>{@code ABANDONED} — resolved markets settle from the map, the rest void ("settle what
 *       resolved, void the rest").
 *   <li>{@code VOIDED} — every selection on the event is VOID (refunded out of the accumulator).
 * </ul>
 */
public record EventResolution(Mode mode, Map<UUID, SettlementResult> selectionOutcomes) {

  public enum Mode {
    COMPLETED,
    ABANDONED,
    VOIDED
  }

  /**
   * The outcome to stamp on a selection of this event, if one can be determined for {@code mode}.
   */
  public Optional<SettlementResult> outcomeFor(UUID selectionId) {
    return switch (mode) {
      case VOIDED -> Optional.of(SettlementResult.VOID);
      case ABANDONED ->
          Optional.of(selectionOutcomes.getOrDefault(selectionId, SettlementResult.VOID));
      case COMPLETED -> Optional.ofNullable(selectionOutcomes.get(selectionId));
    };
  }
}
