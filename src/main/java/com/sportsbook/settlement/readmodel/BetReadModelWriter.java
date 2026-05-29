package com.sportsbook.settlement.readmodel;

import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.domain.BetSelection;
import com.sportsbook.settlement.persistence.BetRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the settlement read model from placement snapshots (ADR-0006 event sourcing). The write is
 * idempotent on {@code betId}: a re-delivered {@code BetPlacedRequested} (consumer redelivery, or a
 * betting-service outbox re-send) is a no-op rather than a duplicate row.
 *
 * <p>Ordering safety: betting publishes BetPlacedRequested keyed by userId, so a given bet's event
 * lands on one partition and is processed single-threaded — the existence check plus insert never
 * races itself for the same betId. The DB primary key on {@code bet_id} is the backstop.
 */
@Service
public class BetReadModelWriter {

  private static final Logger log = LoggerFactory.getLogger(BetReadModelWriter.class);

  private final BetRepository bets;
  private final Clock clock;

  public BetReadModelWriter(BetRepository bets, Clock clock) {
    this.bets = bets;
    this.clock = clock;
  }

  /**
   * Inserts the bet snapshot if absent. Returns {@code true} if a new row was written, {@code
   * false} if the bet was already known (idempotent replay).
   */
  @Transactional
  public boolean record(BetPlacement placement) {
    if (bets.existsById(placement.betId())) {
      log.debug("Skipping already-known bet {} (idempotent replay)", placement.betId());
      return false;
    }
    List<BetSelection> selections =
        placement.selections().stream()
            .map(s -> BetSelection.create(s.eventId(), s.marketId(), s.selectionId(), s.odds()))
            .toList();
    Bet bet =
        Bet.fromPlacement(
            placement.betId(),
            placement.userId(),
            placement.slipType(),
            placement.systemMinWins(),
            placement.systemTotalSelections(),
            placement.stake(),
            placement.requestedAt(),
            selections,
            clock.instant());
    bets.save(bet);
    log.debug(
        "Recorded bet {} ({} selections, slip={})",
        placement.betId(),
        selections.size(),
        placement.slipType());
    return true;
  }
}
