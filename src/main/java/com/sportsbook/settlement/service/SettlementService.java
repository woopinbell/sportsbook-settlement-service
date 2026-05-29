package com.sportsbook.settlement.service;

import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.event.VoidReason;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.client.CreditSource;
import com.sportsbook.settlement.client.WalletCreditClient;
import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.domain.BetSelection;
import com.sportsbook.settlement.outbox.OutboxEventRepository;
import com.sportsbook.settlement.outbox.SettlementEventFactory;
import com.sportsbook.settlement.persistence.BetRepository;
import com.sportsbook.settlement.resolver.ResolvedSelection;
import com.sportsbook.settlement.resolver.SettlementOutcome;
import com.sportsbook.settlement.resolver.SettlementResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates the settlement of one bet against one of its events (ADR-0006). Two-phase so the
 * synchronous wallet credit never runs inside an open DB transaction:
 *
 * <ol>
 *   <li><b>prepare</b> (tx + row lock) — stamp the event's per-selection outcomes; if the bet's
 *       other events are still pending, commit the stamps and stop; otherwise resolve the slip;
 *   <li><b>credit</b> (no tx) — credit the wallet for winnings / refunds, each under a
 *       deterministic {@code Idempotency-Key} so a retry never double-pays;
 *   <li><b>finalize</b> (tx) — re-check PENDING, flip to SETTLED / VOIDED and write the outbox
 *       event.
 * </ol>
 *
 * <p>Idempotency / concurrency: the PENDING re-guard in finalize plus the per-leg idempotency keys
 * mean a redelivered MatchResult, a replay, or two events of a multi arriving at once never produce
 * a double settlement or a double payout. The row lock in prepare serializes a multi's events so
 * the "all resolved?" check is consistent.
 *
 * <p>Wallet legs (wallet model): a WON bet releases the held stake from {@code LOCKED} (refund leg)
 * and pays the profit from {@code HOUSE}; PUSH / VOID refund the held stake; LOST credits nothing
 * (the stake was already debited at placement and stays captured in V1).
 */
@Service
public class SettlementService {

  private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

  private final BetRepository bets;
  private final SettlementResolver resolver;
  private final WalletCreditClient wallet;
  private final OutboxEventRepository outbox;
  private final SettlementEventFactory events;
  private final TransactionTemplate tx;
  private final Clock clock;

  // Seven collaborators, each a distinct concern (read model, domain maths, wallet, outbox, event
  // shape, txn boundary, clock). A holder would just move the parameter pressure down a layer.
  @SuppressWarnings("checkstyle:ParameterNumber")
  public SettlementService(
      BetRepository bets,
      SettlementResolver resolver,
      WalletCreditClient wallet,
      OutboxEventRepository outbox,
      SettlementEventFactory events,
      TransactionTemplate tx,
      Clock clock) {
    this.bets = bets;
    this.resolver = resolver;
    this.wallet = wallet;
    this.outbox = outbox;
    this.events = events;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Settles a bet against a resolved event. No-op (idempotent) if the bet is already terminal or if
   * not all of its events have resolved yet.
   */
  public void settleOnEvent(UUID betId, UUID eventId, EventResolution resolution) {
    Prepared prepared = tx.execute(status -> prepare(betId, eventId, resolution));
    if (prepared == null) {
      return;
    }
    creditWallet(prepared);
    tx.executeWithoutResult(status -> finalizeSettled(prepared));
  }

  /**
   * Voids a bet on a cancelled / postponed event (ADR-0012): refund the full committed stake and
   * publish BetVoided. Idempotent on PENDING.
   */
  public void voidOnEvent(UUID betId, UUID eventId, VoidReason reason) {
    VoidPrepared prepared = tx.execute(status -> prepareVoid(betId));
    if (prepared == null) {
      return;
    }
    wallet.credit(
        "void:refund:" + betId, prepared.userId(), prepared.committed(), CreditSource.USER_LOCKED);
    tx.executeWithoutResult(status -> finalizeVoided(prepared, eventId, reason));
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 1: prepare under a row lock.
  // ---------------------------------------------------------------------------------------------

  private Prepared prepare(UUID betId, UUID eventId, EventResolution resolution) {
    Bet bet = bets.findForUpdateById(betId).orElse(null);
    if (bet == null || !bet.isPending()) {
      return null;
    }
    Map<UUID, SettlementResult> stamps = new HashMap<>();
    for (BetSelection selection : bet.selections()) {
      if (selection.eventId().equals(eventId) && selection.outcome() == null) {
        resolution
            .outcomeFor(selection.selectionId())
            .ifPresent(o -> stamps.put(selection.selectionId(), o));
      }
    }
    bet.applyEventOutcomes(eventId, stamps, clock.instant());
    if (!bet.allSelectionsResolved()) {
      log.debug("Bet {} not fully resolved yet after event {}", betId, eventId);
      return null; // stamps persist on commit; wait for the remaining events.
    }
    List<ResolvedSelection> resolved =
        bet.selections().stream().map(s -> new ResolvedSelection(s.odds(), s.outcome())).toList();
    SettlementOutcome outcome = resolver.resolve(bet.betSlipType(), resolved, bet.stake());
    Money committed =
        bet.stake().multiply(resolver.lineCount(bet.betSlipType(), bet.selections().size()));
    return new Prepared(betId, bet.userId(), eventId, committed, outcome);
  }

  private VoidPrepared prepareVoid(UUID betId) {
    Bet bet = bets.findForUpdateById(betId).orElse(null);
    if (bet == null || !bet.isPending()) {
      return null;
    }
    Money committed =
        bet.stake().multiply(resolver.lineCount(bet.betSlipType(), bet.selections().size()));
    return new VoidPrepared(betId, bet.userId(), committed);
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 2: wallet credit (outside any transaction; idempotent keys).
  // ---------------------------------------------------------------------------------------------

  private void creditWallet(Prepared p) {
    SettlementOutcome outcome = p.outcome();
    switch (outcome.result()) {
      case WON -> {
        creditRefund(p, p.committed());
        Money profit = outcome.payout().subtract(p.committed());
        if (profit.isPositive()) {
          wallet.credit("settle:payout:" + p.betId(), p.userId(), profit, CreditSource.HOUSE_POOL);
        }
      }
      case PUSH, VOID -> creditRefund(p, outcome.payout());
      case LOST -> {
        // Stake was already debited at placement; nothing is credited.
      }
      default -> throw new IllegalStateException("Unexpected result " + outcome.result());
    }
  }

  private void creditRefund(Prepared p, Money amount) {
    if (amount.isPositive()) {
      wallet.credit("settle:refund:" + p.betId(), p.userId(), amount, CreditSource.USER_LOCKED);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 3: finalize (re-guard PENDING, transition + outbox in one tx).
  // ---------------------------------------------------------------------------------------------

  private void finalizeSettled(Prepared p) {
    Bet bet = bets.findForUpdateById(p.betId()).orElseThrow();
    if (!bet.isPending()) {
      return; // a concurrent settle already won.
    }
    Instant now = clock.instant();
    bet.recordSettled(p.outcome().result(), p.outcome().payout(), now);
    outbox.save(
        events.settled(
            p.betId(),
            p.userId(),
            p.eventId(),
            p.outcome().result(),
            p.committed(),
            p.outcome().payout(),
            now));
    log.debug(
        "Settled bet {} -> {} payout {}", p.betId(), p.outcome().result(), p.outcome().payout());
  }

  private void finalizeVoided(VoidPrepared p, UUID eventId, VoidReason reason) {
    Bet bet = bets.findForUpdateById(p.betId()).orElseThrow();
    if (!bet.isPending()) {
      return;
    }
    Instant now = clock.instant();
    bet.recordVoided(p.committed(), now);
    outbox.save(events.voided(p.betId(), p.userId(), eventId, reason, p.committed(), now));
    log.debug("Voided bet {} ({}) refund {}", p.betId(), reason, p.committed());
  }

  private record Prepared(
      UUID betId, UUID userId, UUID eventId, Money committed, SettlementOutcome outcome) {}

  private record VoidPrepared(UUID betId, UUID userId, Money committed) {}
}
