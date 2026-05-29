package com.sportsbook.settlement.service;

import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.settlement.domain.MatchOutcomeMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The settle-time decision a MatchResult carries for one event, decoupled from Avro. {@code mode}
 * (see {@link MatchOutcomeMode}) decides how a selection's outcome is derived from {@code
 * selectionOutcomes} (the resultDetail contract): COMPLETED reads the map (missing = unresolved),
 * ABANDONED voids what the map omits, VOIDED voids everything.
 */
public record EventResolution(
    MatchOutcomeMode mode, Map<UUID, SettlementResult> selectionOutcomes) {

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
