package com.sportsbook.settlement.api;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.domain.Bet;
import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a bet's settlement state ({@code GET /internal/v1/settlements/{betId}}). {@code
 * result} / {@code payout} / {@code settledAt} are null while the bet is still PENDING.
 */
public record SettlementView(
    UUID betId, String status, String result, Money payout, Instant settledAt) {

  public static SettlementView from(Bet bet) {
    return new SettlementView(
        bet.betId(),
        bet.status().name(),
        bet.result() == null ? null : bet.result().name(),
        bet.payout(),
        bet.settledAt());
  }
}
