package com.sportsbook.settlement.readmodel;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.domain.SlipKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Decoded, Avro-free snapshot of a {@code BetPlacedRequested} event — the input to the read-model
 * writer. Keeping the event layer's Avro types out of the persistence layer means the read model
 * depends only on domain value objects.
 *
 * <p>{@code systemMinWins} / {@code systemTotalSelections} are non-null only for SYSTEM slips.
 */
public record BetPlacement(
    UUID betId,
    UUID userId,
    SlipKind slipType,
    Integer systemMinWins,
    Integer systemTotalSelections,
    Money stake,
    Instant requestedAt,
    List<Selection> selections) {

  /** One picked selection with the submission odds (decimal, scale 4). */
  public record Selection(UUID eventId, UUID marketId, UUID selectionId, BigDecimal odds) {}
}
