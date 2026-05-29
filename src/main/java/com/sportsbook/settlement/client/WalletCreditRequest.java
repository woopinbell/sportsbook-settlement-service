package com.sportsbook.settlement.client;

import com.sportsbook.protocol.value.Money;
import java.util.UUID;

/**
 * Wire body for {@code POST /internal/v1/wallet/transactions/credit} (wallet's CreditRequest).
 * {@code source} is the string value of wallet's {@code CreditCommand.Source} — USER_LOCKED for a
 * stake refund, HOUSE_POOL for a profit payout.
 */
public record WalletCreditRequest(UUID userId, Money amount, String source) {}
