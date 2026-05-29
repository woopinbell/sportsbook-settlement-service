package com.sportsbook.settlement.domain;

import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * settlement-service's read-model aggregate for a single bet (ADR-0006 event sourcing). Built
 * PENDING from a {@code BetPlacedRequested} event — settlement never reads the betting-service DB —
 * and later transitioned to SETTLED / VOIDED once the bet's events resolve.
 *
 * <p>Holds two concerns in one row: the immutable placement snapshot (slip shape, stake,
 * selections, submission odds) and the mutable settlement outcome (status, result, payout). Stake
 * is the per-line (unit) stake, matching betting-service's convention; the resolver re-derives the
 * line count from {@link #betSlipType()} (Single/Multiple = 1 line, System = C(N, K) lines).
 */
@Entity
@Table(name = "bet")
public class Bet {

  @Id
  @Column(name = "bet_id", nullable = false, updatable = false)
  private UUID betId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "slip_type", nullable = false, length = 16, updatable = false)
  private SlipKind slipType;

  @Column(name = "system_min_wins", updatable = false)
  private Integer systemMinWins;

  @Column(name = "system_total_selections", updatable = false)
  private Integer systemTotalSelections;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "amount", column = @Column(name = "stake_amount", nullable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "stake_currency", nullable = false, length = 3))
  })
  private EmbeddedMoney stake;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private SettlementStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "result", length = 8)
  private SettlementResult result;

  @Column(name = "payout_amount")
  private Long payoutAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "payout_currency", length = 3)
  private Currency payoutCurrency;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;

  @Column(name = "settled_at")
  private Instant settledAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "bet", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<BetSelection> selections = new ArrayList<>();

  protected Bet() {
    // Required by JPA.
  }

  // Six value-objects; a builder would obscure the call site and this is the only constructor.
  @SuppressWarnings("checkstyle:ParameterNumber")
  private Bet(
      UUID betId,
      UUID userId,
      SlipKind slipType,
      Integer systemMinWins,
      Integer systemTotalSelections,
      Money stake,
      Instant requestedAt,
      Instant now) {
    this.betId = Objects.requireNonNull(betId, "betId");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.slipType = Objects.requireNonNull(slipType, "slipType");
    this.systemMinWins = systemMinWins;
    this.systemTotalSelections = systemTotalSelections;
    this.stake = EmbeddedMoney.of(stake);
    this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    this.status = SettlementStatus.PENDING;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  /**
   * Builds a PENDING read-model bet from a placement snapshot and attaches its selections (in the
   * given order). SYSTEM slips must supply {@code systemMinWins} (K) + {@code
   * systemTotalSelections} (N); SINGLE / MULTIPLE pass null for both.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static Bet fromPlacement(
      UUID betId,
      UUID userId,
      SlipKind slipType,
      Integer systemMinWins,
      Integer systemTotalSelections,
      Money stake,
      Instant requestedAt,
      List<BetSelection> selections,
      Instant now) {
    Bet bet =
        new Bet(
            betId, userId, slipType, systemMinWins, systemTotalSelections, stake, requestedAt, now);
    int index = 0;
    for (BetSelection selection : selections) {
      selection.assignTo(bet, index++);
      bet.selections.add(selection);
    }
    return bet;
  }

  /**
   * Reconstructs the {@link BetSlipType} sealed shape the resolvers consume. SYSTEM revalidates K /
   * N via the record's invariants.
   */
  public BetSlipType betSlipType() {
    return switch (slipType) {
      case SINGLE -> new BetSlipType.Single();
      case MULTIPLE -> new BetSlipType.Multiple();
      case SYSTEM -> new BetSlipType.System(systemMinWins, systemTotalSelections);
    };
  }

  public UUID betId() {
    return betId;
  }

  public UUID userId() {
    return userId;
  }

  public SlipKind slipType() {
    return slipType;
  }

  public Money stake() {
    return stake.toMoney();
  }

  public SettlementStatus status() {
    return status;
  }

  public SettlementResult result() {
    return result;
  }

  public Money payout() {
    return payoutAmount == null ? null : new Money(payoutAmount, payoutCurrency);
  }

  public Instant requestedAt() {
    return requestedAt;
  }

  public Instant settledAt() {
    return settledAt;
  }

  public boolean isPending() {
    return status == SettlementStatus.PENDING;
  }

  /** Selections in submission order. Unmodifiable — mutate through aggregate methods. */
  public List<BetSelection> selections() {
    return Collections.unmodifiableList(selections);
  }
}
