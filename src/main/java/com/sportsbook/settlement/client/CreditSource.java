package com.sportsbook.settlement.client;

/**
 * Which bucket the wallet credit draws from — the string value matches wallet-service's {@code
 * CreditCommand.Source} enum on the wire.
 *
 * <ul>
 *   <li>{@code USER_LOCKED} — release the held stake from the user's locked bucket (refund leg of a
 *       win, or a push / void / cancelled-event refund). LedgerReason BET_REFUND.
 *   <li>{@code HOUSE_POOL} — pay winnings (profit) from the house account. LedgerReason BET_PAYOUT.
 * </ul>
 */
public enum CreditSource {
  USER_LOCKED,
  HOUSE_POOL
}
