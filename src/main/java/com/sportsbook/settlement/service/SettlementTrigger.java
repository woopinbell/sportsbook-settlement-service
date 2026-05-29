package com.sportsbook.settlement.service;

import com.sportsbook.protocol.event.VoidReason;
import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.persistence.BetRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fans a resolved event out to the bets that touch it (ADR-0006 / ADR-0017 settlement). On a
 * MatchResult it settles every PENDING bet with a selection on the event; on a cancelled /
 * postponed event it voids them (ADR-0012). The per-bet work — stamping, resolving, crediting,
 * transition — is the idempotent {@link SettlementService}; this layer is just the lookup + loop,
 * so a redelivery or an admin replay over the same event is safe.
 *
 * <p>Bets whose BetPlacedRequested has not yet been consumed when the result arrives are simply not
 * found here; they settle on a later replay (admin-api) once the read model catches up.
 */
@Service
public class SettlementTrigger {

  private static final Logger log = LoggerFactory.getLogger(SettlementTrigger.class);

  private final BetRepository bets;
  private final SettlementService settlement;

  public SettlementTrigger(BetRepository bets, SettlementService settlement) {
    this.bets = bets;
    this.settlement = settlement;
  }

  /** Settles every PENDING bet on {@code eventId} against the event's resolution. */
  public void onMatchResult(UUID eventId, EventResolution resolution) {
    List<Bet> pending = bets.findPendingByEvent(eventId);
    log.debug(
        "MatchResult event={} mode={} -> {} pending bet(s)",
        eventId,
        resolution.mode(),
        pending.size());
    for (Bet bet : pending) {
      settlement.settleOnEvent(bet.betId(), eventId, resolution);
    }
  }

  /** Voids every PENDING bet on a cancelled / postponed {@code eventId} (full stake refund). */
  public void onEventCancelled(UUID eventId, VoidReason reason) {
    List<Bet> pending = bets.findPendingByEvent(eventId);
    log.debug("Event {} {} -> voiding {} pending bet(s)", eventId, reason, pending.size());
    for (Bet bet : pending) {
      settlement.voidOnEvent(bet.betId(), eventId, reason);
    }
  }
}
