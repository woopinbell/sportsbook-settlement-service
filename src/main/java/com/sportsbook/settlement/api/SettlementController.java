package com.sportsbook.settlement.api;

import com.sportsbook.settlement.error.SettlementConflictException;
import com.sportsbook.settlement.error.SettlementNotFoundException;
import com.sportsbook.settlement.persistence.BetRepository;
import com.sportsbook.settlement.service.SettlementService;
import com.sportsbook.settlement.service.SettlementTrigger;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal operational REST surface (ADR-0004, {@code /internal/v1} prefix; admin-api is the
 * authenticated front door). Settlement itself is event-driven — these endpoints are for status
 * lookup and the two operator actions: replay an event's settlement and manually void a bet.
 * Exceptions become RFC 7807 responses via {@link SettlementExceptionHandler}.
 */
@RestController
@RequestMapping("/internal/v1/settlements")
public class SettlementController {

  private final BetRepository bets;
  private final SettlementService settlement;
  private final SettlementTrigger trigger;

  public SettlementController(
      BetRepository bets, SettlementService settlement, SettlementTrigger trigger) {
    this.bets = bets;
    this.settlement = settlement;
    this.trigger = trigger;
  }

  /** Settlement state of a bet. */
  @GetMapping("/{betId}")
  public SettlementView get(@PathVariable UUID betId) {
    return bets.findById(betId)
        .map(SettlementView::from)
        .orElseThrow(() -> new SettlementNotFoundException("bet", betId));
  }

  /**
   * Re-settles the still-PENDING bets on an event from its materialized result (operator action,
   * e.g. after a DLQ drain or a late correction past the auto window, ADR-0012). 404 if the event
   * has no materialized result yet.
   */
  @PostMapping("/replay/{eventId}")
  public ResponseEntity<Void> replay(@PathVariable UUID eventId) {
    if (!trigger.replay(eventId)) {
      throw new SettlementNotFoundException("match result for event", eventId);
    }
    return ResponseEntity.accepted().build();
  }

  /**
   * Manually voids a bet and refunds the stake (operator action). 409 if the bet is not PENDING.
   */
  @PostMapping("/void/{betId}")
  public ResponseEntity<Void> voidBet(@PathVariable UUID betId) {
    if (!bets.existsById(betId)) {
      throw new SettlementNotFoundException("bet", betId);
    }
    if (!settlement.voidByAdmin(betId)) {
      throw new SettlementConflictException("bet " + betId + " is not PENDING; cannot void");
    }
    return ResponseEntity.ok().build();
  }
}
