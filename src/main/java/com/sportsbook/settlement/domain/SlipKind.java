package com.sportsbook.settlement.domain;

import com.sportsbook.protocol.domain.BetSlipType;

/**
 * Persistence-friendly discriminator for {@link BetSlipType} (ADR-0008). The sealed interface is
 * the domain shape used by the resolvers; this flat enum is what the read-model row stores. SYSTEM
 * rows additionally carry {@code system_min_wins} (K) + {@code system_total_selections} (N).
 */
public enum SlipKind {
  SINGLE,
  MULTIPLE,
  SYSTEM
}
