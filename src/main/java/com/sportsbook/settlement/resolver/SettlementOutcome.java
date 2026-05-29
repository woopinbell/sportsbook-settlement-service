package com.sportsbook.settlement.resolver;

import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Money;
import java.util.Objects;

/**
 * Result of resolving a whole bet slip: the slip-level {@link SettlementResult} and the {@link
 * Money} to credit the wallet.
 *
 * <ul>
 *   <li>WON — {@code payout} is the winnings (stake × line products), credited from the house.
 *   <li>PUSH / VOID — {@code payout} equals the total stake, refunded from the locked bucket.
 *   <li>LOST — {@code payout} is zero; nothing is credited (the stake was already debited at
 *       placement).
 * </ul>
 */
public record SettlementOutcome(SettlementResult result, Money payout) {

  public SettlementOutcome {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(payout, "payout");
  }

  /** True when the wallet must be credited (winnings or refund). LOST credits nothing. */
  public boolean requiresCredit() {
    return result != SettlementResult.LOST && payout.isPositive();
  }
}
