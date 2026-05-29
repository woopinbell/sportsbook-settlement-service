package com.sportsbook.settlement.outbox;

import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.event.BetSettled;
import com.sportsbook.protocol.event.BetVoided;
import com.sportsbook.protocol.event.SettlementResultAvro;
import com.sportsbook.protocol.event.VoidReason;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.config.SettlementTopics;
import com.sportsbook.settlement.infrastructure.id.UuidV7;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code BetSettled} / {@code BetVoided} outbox rows from a settled bet (ADR-0006).
 * Keeps the Avro construction out of the settlement service.
 *
 * <p>Partition key is the driving {@code eventId} (not userId): ADR-0006 routes a match's
 * settlement stream to one partition so a bet's settle/void and any later correction stay ordered.
 * The Avro payload is binary-encoded with no schema id (V1, ADR-0014).
 */
@Component
public class SettlementEventFactory {

  static final String SETTLED_SCHEMA = "BetSettled";
  static final String VOIDED_SCHEMA = "BetVoided";

  private final SettlementTopics topics;

  public SettlementEventFactory(SettlementTopics topics) {
    this.topics = topics;
  }

  /**
   * BetSettled: a resolved bet (WON / LOST / PUSH / VOID). {@code payout} is the credited amount.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public OutboxEvent settled(
      UUID betId,
      UUID userId,
      UUID eventId,
      SettlementResult result,
      Money stake,
      Money payout,
      Instant now) {
    BetSettled record =
        BetSettled.newBuilder()
            .setBetId(betId.toString())
            .setUserId(userId.toString())
            .setEventId(eventId.toString())
            .setResult(SettlementResultAvro.valueOf(result.name()))
            .setStake(toAvroMoney(stake))
            .setPayout(toAvroMoney(payout))
            .setSettledAt(now)
            .setResultDetail(null)
            .build();
    return OutboxEvent.pending(
        UuidV7.generate(),
        topics.betSettled(),
        eventId.toString(),
        SETTLED_SCHEMA,
        AvroSerializer.serialize(record),
        now);
  }

  /** BetVoided: a whole-bet refund on a cancelled / postponed event (ADR-0012). */
  public OutboxEvent voided(
      UUID betId, UUID userId, UUID eventId, VoidReason reason, Money refund, Instant now) {
    BetVoided record =
        BetVoided.newBuilder()
            .setBetId(betId.toString())
            .setUserId(userId.toString())
            .setEventId(eventId.toString())
            .setReason(reason)
            .setRefund(toAvroMoney(refund))
            .setVoidedAt(now)
            .build();
    return OutboxEvent.pending(
        UuidV7.generate(),
        topics.betVoided(),
        eventId.toString(),
        VOIDED_SCHEMA,
        AvroSerializer.serialize(record),
        now);
  }

  private static com.sportsbook.protocol.event.Money toAvroMoney(Money money) {
    return com.sportsbook.protocol.event.Money.newBuilder()
        .setAmount(money.amount())
        .setCurrency(money.currency().name())
        .build();
  }
}
