package com.sportsbook.settlement.resolver;

import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * Resolves a bet slip to a {@link SettlementResult} + payout (ADR-0008). Single, Multiple and
 * System are the same maths over <b>lines</b> (accumulators), mirroring betting-service's
 * SystemBetCalculator so the amount settled here reconciles with the max-payout it computed at
 * placement:
 *
 * <ul>
 *   <li><b>Single</b> — 1 line of the 1 selection.
 *   <li><b>Multiple</b> — 1 line of all N selections (all must survive).
 *   <li><b>System(K-of-N)</b> — C(N, K) lines, one per K-subset; each winning line pays.
 * </ul>
 *
 * <p>Each selection maps to a per-line multiplier from its outcome: WON contributes its decimal
 * odds; PUSH / VOID contribute 1.0 (the leg is refunded out of the accumulator — ADR-0012 "void the
 * selection, recompute the rest"); LOST kills the line it is on. So
 *
 * <pre>payout = unitStake × Σ_lines Π_legs multiplier   (a dead line contributes 0)</pre>
 *
 * <p>{@code stake} is the per-line (unit) stake, matching betting. Rounding floors once on the
 * summed line products (house-favourable, single rounding step), identical to betting's max-payout
 * floor so an all-win settlement equals the placement-time figure.
 *
 * <p>half-won / half-lost are out of V1 scope (ADR-0012); a selection is always a whole WON / LOST
 * / PUSH / VOID, so no fractional multiplier ever appears.
 */
@Component
public class SettlementResolver {

  /** Resolves the slip. {@code stake} is the per-line unit stake; selections are in slip order. */
  public SettlementOutcome resolve(
      BetSlipType type, List<ResolvedSelection> selections, Money stake) {
    BigDecimal summedLineProducts = BigDecimal.ZERO;
    for (List<Integer> line : lines(type, selections.size())) {
      summedLineProducts = summedLineProducts.add(lineProduct(line, selections));
    }
    long payoutAmount =
        BigDecimal.valueOf(stake.amount())
            .multiply(summedLineProducts)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact();
    Money payout = new Money(payoutAmount, stake.currency());
    return new SettlementOutcome(classify(selections, payoutAmount), payout);
  }

  /**
   * Product of one line's leg multipliers, or {@link BigDecimal#ZERO} if any leg LOST (the whole
   * line is dead). WON legs multiply by their odds; PUSH / VOID legs multiply by 1.0.
   */
  private static BigDecimal lineProduct(List<Integer> line, List<ResolvedSelection> selections) {
    BigDecimal product = BigDecimal.ONE;
    for (int index : line) {
      ResolvedSelection selection = selections.get(index);
      switch (selection.outcome()) {
        case LOST -> {
          return BigDecimal.ZERO;
        }
        case WON -> product = product.multiply(selection.odds().decimal());
        case PUSH, VOID -> {
          // Leg refunded out of the accumulator: multiplier 1.0, no-op on the product.
        }
        default -> throw new IllegalStateException("Unexpected outcome " + selection.outcome());
      }
    }
    return product;
  }

  /**
   * Slip-level result. Payout 0 means every line died (at least one LOST per line) → LOST.
   * Otherwise any WON leg makes it a win; with no WON and no LOST the slip is a full refund — VOID
   * if every leg voided, else PUSH.
   */
  private static SettlementResult classify(List<ResolvedSelection> selections, long payoutAmount) {
    if (payoutAmount == 0L) {
      return SettlementResult.LOST;
    }
    boolean anyWon = selections.stream().anyMatch(s -> s.outcome() == SettlementResult.WON);
    if (anyWon) {
      return SettlementResult.WON;
    }
    boolean allVoid = selections.stream().allMatch(s -> s.outcome() == SettlementResult.VOID);
    return allVoid ? SettlementResult.VOID : SettlementResult.PUSH;
  }

  /**
   * Number of lines (accumulators) the slip expands into — the multiplier on the unit stake that
   * gives the total committed stake. Single/Multiple = 1; System = C(N, K). Symmetric with
   * betting-service so the committed amount reconciles with the placement-time debit.
   */
  public int lineCount(BetSlipType type, int legCount) {
    if (type instanceof BetSlipType.System system) {
      return Math.toIntExact(binomial(legCount, system.minWins()));
    }
    return 1;
  }

  /** Lines the slip expands into: Single/Multiple = one line over all legs, System = C(N, K). */
  private static List<List<Integer>> lines(BetSlipType type, int legCount) {
    if (type instanceof BetSlipType.System system) {
      return combinations(legCount, system.minWins());
    }
    return List.of(IntStream.range(0, legCount).boxed().toList());
  }

  /** C(n, k) via the multiplicative formula; exact for the L4-bounded range (n &le; 15). */
  static long binomial(int n, int k) {
    if (k < 0 || k > n) {
      return 0;
    }
    int kk = Math.min(k, n - k);
    long result = 1;
    for (int i = 0; i < kk; i++) {
      result = result * (n - i) / (i + 1);
    }
    return result;
  }

  /** All {@code k}-subsets of {@code [0, n)}, ascending, lexicographic. */
  static List<List<Integer>> combinations(int n, int k) {
    List<List<Integer>> out = new ArrayList<>();
    collect(0, n, k, new ArrayList<>(), out);
    return out;
  }

  private static void collect(
      int start, int n, int k, List<Integer> current, List<List<Integer>> out) {
    if (current.size() == k) {
      out.add(new ArrayList<>(current));
      return;
    }
    // Prune: stop when too few remaining elements to reach size k.
    for (int i = start; i <= n - (k - current.size()); i++) {
      current.add(i);
      collect(i + 1, n, k, current, out);
      current.remove(current.size() - 1);
    }
  }
}
