package com.sportsbook.settlement.service;

import com.sportsbook.protocol.event.VoidReason;
import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.domain.MatchResultRecord;
import com.sportsbook.settlement.persistence.BetRepository;
import com.sportsbook.settlement.persistence.MatchResultRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fans a resolved event out to the bets that touch it (ADR-0006 / ADR-0017 settlement). On a
 * MatchResult it materializes the result (for replay + late-bet settlement) and settles every
 * PENDING bet with a selection on the event; on a cancelled / postponed event it voids them
 * (ADR-0012). The per-bet work — stamping, resolving, crediting, transition — is the idempotent
 * {@link SettlementService}, so a redelivery or an admin replay over the same event is safe.
 *
 * <p>Bets whose BetPlacedRequested has not been consumed when the result arrives are simply not
 * found in the fan-out; a later {@link #replay(UUID)} (admin-api) re-settles them off the stored
 * MatchResultRecord once the read model catches up.
 */
@Service
public class SettlementTrigger {

  private static final Logger log = LoggerFactory.getLogger(SettlementTrigger.class);

  private final BetRepository bets;
  private final MatchResultRepository matchResults;
  private final SettlementService settlement;
  private final Clock clock;

  public SettlementTrigger(
      BetRepository bets,
      MatchResultRepository matchResults,
      SettlementService settlement,
      Clock clock) {
    this.bets = bets;
    this.matchResults = matchResults;
    this.settlement = settlement;
    this.clock = clock;
  }

  /** Materializes the result then settles every PENDING bet on the event. */
  public void onMatchResult(UUID eventId, EventResolution resolution, Instant settledAt) {
    matchResults.save(
        MatchResultRecord.of(
            eventId,
            resolution.mode(),
            resolution.selectionOutcomes(),
            settledAt,
            clock.instant()));
    fanOutSettle(eventId, resolution);
  }

  /**
   * Re-settles every PENDING bet on {@code eventId} from the stored MatchResultRecord (admin replay
   * + late-bet catch-up). Returns false if the event has no materialized result yet.
   */
  public boolean replay(UUID eventId) {
    return matchResults
        .findById(eventId)
        .map(
            record -> {
              fanOutSettle(eventId, new EventResolution(record.mode(), record.selectionOutcomes()));
              return true;
            })
        .orElse(false);
  }

  /** Voids every PENDING bet on a cancelled / postponed {@code eventId} (full stake refund). */
  public void onEventCancelled(UUID eventId, VoidReason reason) {
    List<Bet> pending = bets.findPendingByEvent(eventId);
    log.debug("Event {} {} -> voiding {} pending bet(s)", eventId, reason, pending.size());
    for (Bet bet : pending) {
      settlement.voidOnEvent(bet.betId(), eventId, reason);
    }
  }

  private void fanOutSettle(UUID eventId, EventResolution resolution) {
    List<Bet> pending = bets.findPendingByEvent(eventId);
    log.debug(
        "Settling event={} mode={} -> {} pending bet(s)",
        eventId,
        resolution.mode(),
        pending.size());
    for (Bet bet : pending) {
      settlement.settleOnEvent(bet.betId(), eventId, resolution);
    }
  }
}
