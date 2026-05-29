package com.sportsbook.settlement.resolver;

import static com.sportsbook.protocol.domain.SettlementResult.LOST;
import static com.sportsbook.protocol.domain.SettlementResult.PUSH;
import static com.sportsbook.protocol.domain.SettlementResult.VOID;
import static com.sportsbook.protocol.domain.SettlementResult.WON;
import static org.assertj.core.api.Assertions.assertThat;

import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the Single / Multiple / System resolution maths (ADR-0008) — the interview-critical core.
 * Payout convention: {@code unitStake × Σ_lines Π_legs multiplier}, multiplier WON=odds,
 * PUSH/VOID=1.0, a LOST leg kills its line; floor once.
 */
class SettlementResolverTest {

  private final SettlementResolver resolver = new SettlementResolver();

  private static ResolvedSelection sel(String odds, SettlementResult outcome) {
    return new ResolvedSelection(Odds.ofDecimal(odds), outcome);
  }

  @Nested
  @DisplayName("Single")
  class Single {

    private final BetSlipType type = new BetSlipType.Single();

    @Test
    void wonPaysStakeTimesOdds() {
      SettlementOutcome out = resolver.resolve(type, List.of(sel("2.0", WON)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(WON);
      assertThat(out.payout()).isEqualTo(Money.krw(20_000));
      assertThat(out.requiresCredit()).isTrue();
    }

    @Test
    void lostPaysNothing() {
      SettlementOutcome out = resolver.resolve(type, List.of(sel("2.0", LOST)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(LOST);
      assertThat(out.payout()).isEqualTo(Money.krw(0));
      assertThat(out.requiresCredit()).isFalse();
    }

    @Test
    void pushRefundsStake() {
      SettlementOutcome out = resolver.resolve(type, List.of(sel("2.0", PUSH)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(PUSH);
      assertThat(out.payout()).isEqualTo(Money.krw(10_000));
    }

    @Test
    void voidRefundsStake() {
      SettlementOutcome out = resolver.resolve(type, List.of(sel("2.0", VOID)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(VOID);
      assertThat(out.payout()).isEqualTo(Money.krw(10_000));
    }

    @Test
    void payoutFloorsToWholeMinorUnits() {
      // 1001 * 1.8500 = 1851.85 -> floor 1851.
      SettlementOutcome out = resolver.resolve(type, List.of(sel("1.85", WON)), Money.krw(1_001));
      assertThat(out.payout()).isEqualTo(Money.krw(1_851));
    }
  }

  @Nested
  @DisplayName("Multiple (all legs must survive)")
  class Multiple {

    private final BetSlipType type = new BetSlipType.Multiple();

    @Test
    void allWonMultipliesOdds() {
      SettlementOutcome out =
          resolver.resolve(type, List.of(sel("2.0", WON), sel("1.5", WON)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(WON);
      assertThat(out.payout()).isEqualTo(Money.krw(30_000)); // 10000 * 2.0 * 1.5
    }

    @Test
    void anyLostLosesTheSlip() {
      SettlementOutcome out =
          resolver.resolve(type, List.of(sel("2.0", WON), sel("1.5", LOST)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(LOST);
      assertThat(out.payout()).isEqualTo(Money.krw(0));
    }

    @Test
    @DisplayName("a VOID leg drops out and the rest recompute (ADR-0012)")
    void voidLegRecomputesOnSurvivors() {
      SettlementOutcome out =
          resolver.resolve(type, List.of(sel("2.0", WON), sel("1.5", VOID)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(WON);
      assertThat(out.payout()).isEqualTo(Money.krw(20_000)); // 10000 * 2.0 * 1.0
    }

    @Test
    void pushLegDropsOutLikeVoid() {
      SettlementOutcome out =
          resolver.resolve(type, List.of(sel("2.0", WON), sel("1.5", PUSH)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(WON);
      assertThat(out.payout()).isEqualTo(Money.krw(20_000));
    }

    @Test
    void allVoidRefundsStakeAsVoid() {
      SettlementOutcome out =
          resolver.resolve(type, List.of(sel("2.0", VOID), sel("1.5", VOID)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(VOID);
      assertThat(out.payout()).isEqualTo(Money.krw(10_000));
    }

    @Test
    void noWinNoLossWithAPushRefundsAsPush() {
      SettlementOutcome out =
          resolver.resolve(type, List.of(sel("2.0", PUSH), sel("1.5", VOID)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(PUSH);
      assertThat(out.payout()).isEqualTo(Money.krw(10_000));
    }

    @Test
    void threeLegsWithOneVoidRecomputeOnTwoWinners() {
      SettlementOutcome out =
          resolver.resolve(
              type, List.of(sel("2.0", WON), sel("1.5", WON), sel("3.0", VOID)), Money.krw(10_000));
      assertThat(out.result()).isEqualTo(WON);
      assertThat(out.payout()).isEqualTo(Money.krw(30_000)); // 10000 * 2.0 * 1.5 * 1.0
    }
  }

  @Nested
  @DisplayName("System (K-of-N): each winning K-subset line pays")
  class System {

    @Test
    @DisplayName("2-of-3 all won sums every line")
    void twoOfThreeAllWon() {
      // unit 1000; lines {01}=6, {02}=8, {12}=12 -> 26 -> 26000.
      SettlementOutcome out =
          resolver.resolve(
              new BetSlipType.System(2, 3),
              List.of(sel("2.0", WON), sel("3.0", WON), sel("4.0", WON)),
              Money.krw(1_000));
      assertThat(out.result()).isEqualTo(WON);
      assertThat(out.payout()).isEqualTo(Money.krw(26_000));
    }

    @Test
    @DisplayName("2-of-3 partial hit: only the all-won line pays")
    void twoOfThreePartial() {
      // sel2 lost: only line {0,1} survives -> 1000 * 2.0 * 3.0 = 6000.
      SettlementOutcome out =
          resolver.resolve(
              new BetSlipType.System(2, 3),
              List.of(sel("2.0", WON), sel("3.0", WON), sel("4.0", LOST)),
              Money.krw(1_000));
      assertThat(out.result()).isEqualTo(WON);
      assertThat(out.payout()).isEqualTo(Money.krw(6_000));
    }

    @Test
    @DisplayName("2-of-3 with only one winner loses (no full line survives)")
    void twoOfThreeOneWinnerLoses() {
      SettlementOutcome out =
          resolver.resolve(
              new BetSlipType.System(2, 3),
              List.of(sel("2.0", WON), sel("3.0", LOST), sel("4.0", LOST)),
              Money.krw(1_000));
      assertThat(out.result()).isEqualTo(LOST);
      assertThat(out.payout()).isEqualTo(Money.krw(0));
    }

    @Test
    @DisplayName("2-of-3 with a VOID leg: lines through it ride at 1.0")
    void twoOfThreeWithVoid() {
      // sel1 void: {01}=2*1=2, {02}=2*4=8, {12}=1*4=4 -> 14 -> 14000.
      SettlementOutcome out =
          resolver.resolve(
              new BetSlipType.System(2, 3),
              List.of(sel("2.0", WON), sel("3.0", VOID), sel("4.0", WON)),
              Money.krw(1_000));
      assertThat(out.result()).isEqualTo(WON);
      assertThat(out.payout()).isEqualTo(Money.krw(14_000));
    }

    @Test
    void allLostLosesEntirely() {
      SettlementOutcome out =
          resolver.resolve(
              new BetSlipType.System(2, 3),
              List.of(sel("2.0", LOST), sel("3.0", LOST), sel("4.0", LOST)),
              Money.krw(1_000));
      assertThat(out.result()).isEqualTo(LOST);
      assertThat(out.payout()).isEqualTo(Money.krw(0));
    }

    @Test
    @DisplayName("2-of-3 all void refunds the whole stake (unit * C(3,2))")
    void allVoidRefundsTotalStake() {
      SettlementOutcome out =
          resolver.resolve(
              new BetSlipType.System(2, 3),
              List.of(sel("2.0", VOID), sel("3.0", VOID), sel("4.0", VOID)),
              Money.krw(1_000));
      assertThat(out.result()).isEqualTo(VOID);
      assertThat(out.payout()).isEqualTo(Money.krw(3_000)); // 3 lines * unit
    }

    @Test
    @DisplayName("1-of-4 (singles cover): the one winning fold pays")
    void oneOfFour() {
      SettlementOutcome out =
          resolver.resolve(
              new BetSlipType.System(1, 4),
              List.of(sel("5.0", WON), sel("2.0", LOST), sel("3.0", LOST), sel("1.5", LOST)),
              Money.krw(1_000));
      assertThat(out.result()).isEqualTo(WON);
      assertThat(out.payout()).isEqualTo(Money.krw(5_000));
    }
  }

  @Nested
  @DisplayName("combination enumeration")
  class Combinations {

    @Test
    void twoOfThree() {
      assertThat(SettlementResolver.combinations(3, 2))
          .containsExactly(List.of(0, 1), List.of(0, 2), List.of(1, 2));
    }

    @Test
    void oneOfFour() {
      assertThat(SettlementResolver.combinations(4, 1))
          .containsExactly(List.of(0), List.of(1), List.of(2), List.of(3));
    }
  }
}
