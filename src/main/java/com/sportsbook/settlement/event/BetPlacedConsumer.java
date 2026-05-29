package com.sportsbook.settlement.event;

import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.event.BetSlipTypeTag;
import com.sportsbook.protocol.event.RequestedSelection;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.domain.SlipKind;
import com.sportsbook.settlement.readmodel.BetPlacement;
import com.sportsbook.settlement.readmodel.BetReadModelWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Event-sources the settlement read model off {@code bet.placed.v1} (ADR-0006). Decodes each {@code
 * BetPlacedRequested}, maps it to an Avro-free {@link BetPlacement}, and hands it to {@link
 * BetReadModelWriter} for an idempotent insert.
 *
 * <p>Ack is manual ({@code manual_immediate}) and only fired after the write commits, so a
 * transient DB failure leaves the offset behind and lets the broker redeliver. The idempotent
 * writer makes that redelivery safe. (Topic is betting-service's actual publish topic {@code
 * bet.placed.v1}; see the settlement.topics note in application.yml about the risk-service
 * mismatch.)
 */
@Component
public class BetPlacedConsumer {

  private static final Logger log = LoggerFactory.getLogger(BetPlacedConsumer.class);

  private final BetReadModelWriter writer;

  public BetPlacedConsumer(BetReadModelWriter writer) {
    this.writer = writer;
  }

  @KafkaListener(topics = "${settlement.topics.bet-placed}", groupId = "settlement.bet-placed")
  public void onBetPlaced(
      @Payload byte[] payload,
      @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
      Acknowledgment ack) {
    BetPlacedRequested event = AvroCodec.decode(payload, BetPlacedRequested.class);
    boolean created = writer.record(toPlacement(event));
    ack.acknowledge();
    log.debug("Consumed bet.placed betId={} key={} created={}", event.getBetId(), key, created);
  }

  private static BetPlacement toPlacement(BetPlacedRequested event) {
    Money stake =
        new Money(
            event.getStake().getAmount(),
            Currency.valueOf(event.getStake().getCurrency().toString()));
    List<BetPlacement.Selection> selections =
        event.getSelections().stream().map(BetPlacedConsumer::toSelection).toList();
    return new BetPlacement(
        UUID.fromString(event.getBetId().toString()),
        UUID.fromString(event.getUserId().toString()),
        toSlipKind(event.getSlipType()),
        event.getSystemMinWins(),
        event.getSystemTotalSelections(),
        stake,
        event.getRequestedAt(),
        selections);
  }

  private static BetPlacement.Selection toSelection(RequestedSelection s) {
    return new BetPlacement.Selection(
        UUID.fromString(s.getEventId().toString()),
        UUID.fromString(s.getMarketId().toString()),
        UUID.fromString(s.getSelectionId().toString()),
        new BigDecimal(s.getOddsAtSubmission().toString()));
  }

  private static SlipKind toSlipKind(BetSlipTypeTag tag) {
    return switch (tag) {
      case SINGLE -> SlipKind.SINGLE;
      case MULTIPLE -> SlipKind.MULTIPLE;
      case SYSTEM -> SlipKind.SYSTEM;
    };
  }
}
