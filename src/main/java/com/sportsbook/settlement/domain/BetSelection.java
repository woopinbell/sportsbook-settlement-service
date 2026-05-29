package com.sportsbook.settlement.domain;

import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Odds;
import com.sportsbook.settlement.infrastructure.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One selection of a bet in settlement-service's read model — the child side of the {@link Bet}
 * aggregate. Rebuilt from a {@code RequestedSelection} on the BetPlacedRequested event: what the
 * user picked ({@code eventId} / {@code marketId} / {@code selectionId}) and the decimal odds shown
 * at submit time. V1 uses those submission odds for payout (no accepted-odds field in the event).
 *
 * <p>{@code legIndex} preserves submission order so the System K-of-N combinations are
 * deterministic and symmetric with betting-service. {@code outcome} is null until this selection's
 * event resolves; it is then set from {@code MatchResult.resultDetail} (settlement never re-derives
 * a result from the raw score).
 */
@Entity
@Table(name = "bet_selection")
public class BetSelection {

  @Id
  @Column(name = "selection_row_id", nullable = false, updatable = false)
  private UUID selectionRowId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "bet_id", nullable = false)
  private Bet bet;

  @Column(name = "leg_index", nullable = false)
  private int legIndex;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "market_id", nullable = false)
  private UUID marketId;

  @Column(name = "selection_id", nullable = false)
  private UUID selectionId;

  @Column(name = "odds", nullable = false, precision = 9, scale = 4)
  private BigDecimal odds;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", length = 8)
  private SettlementResult outcome;

  protected BetSelection() {
    // Required by JPA.
  }

  private BetSelection(UUID eventId, UUID marketId, UUID selectionId, BigDecimal odds) {
    this.selectionRowId = UuidV7.generate();
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.marketId = Objects.requireNonNull(marketId, "marketId");
    this.selectionId = Objects.requireNonNull(selectionId, "selectionId");
    this.odds = Objects.requireNonNull(odds, "odds");
  }

  /**
   * Creates a detached selection. The parent {@link Bet} sets the back-reference and {@link
   * #legIndex} on attach, so a selection is only ever persisted as part of its aggregate.
   */
  public static BetSelection create(
      UUID eventId, UUID marketId, UUID selectionId, BigDecimal odds) {
    return new BetSelection(eventId, marketId, selectionId, odds);
  }

  // Package-private: only Bet may wire the back-reference + order, keeping the aggregate
  // consistent.
  void assignTo(Bet bet, int legIndex) {
    this.bet = Objects.requireNonNull(bet, "bet");
    this.legIndex = legIndex;
  }

  // Package-private: outcome is stamped only through Bet.applyEventOutcomes, write-once.
  void recordOutcome(SettlementResult result) {
    this.outcome = Objects.requireNonNull(result, "result");
  }

  public UUID selectionRowId() {
    return selectionRowId;
  }

  public int legIndex() {
    return legIndex;
  }

  public UUID eventId() {
    return eventId;
  }

  public UUID marketId() {
    return marketId;
  }

  public UUID selectionId() {
    return selectionId;
  }

  public Odds odds() {
    return Odds.ofDecimal(odds);
  }

  public SettlementResult outcome() {
    return outcome;
  }
}
