package com.sportsbook.settlement.event;

import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.event.MatchFinalStatus;
import com.sportsbook.protocol.event.MatchResult;
import com.sportsbook.settlement.domain.MatchOutcomeMode;
import com.sportsbook.settlement.service.EventResolution;
import com.sportsbook.settlement.service.SettlementTrigger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code match.result} (odds-feed-service) and drives settlement (ADR-0006). Maps the Avro
 * MatchResult to an {@link EventResolution}: {@code finalStatus} becomes the mode and {@code
 * resultDetail} is parsed as the per-selection outcome contract (selectionId → WON/LOST/PUSH/VOID).
 * settlement never re-derives an outcome from the raw score — it reads the contract here (boundary:
 * it does not decide results).
 *
 * <p>A malformed entry (bad UUID or unknown result) throws, so the message is not acked and is
 * retried / dead-lettered rather than silently mis-settled.
 */
@Component
public class MatchResultConsumer {

  private static final Logger log = LoggerFactory.getLogger(MatchResultConsumer.class);

  private final SettlementTrigger trigger;

  public MatchResultConsumer(SettlementTrigger trigger) {
    this.trigger = trigger;
  }

  @KafkaListener(topics = "${settlement.topics.match-result}", groupId = "settlement.match-result")
  public void onMatchResult(@Payload byte[] payload, Acknowledgment ack) {
    MatchResult event = AvroCodec.decode(payload, MatchResult.class);
    UUID eventId = UUID.fromString(event.getEventId().toString());
    EventResolution resolution = new EventResolution(mode(event.getFinalStatus()), outcomes(event));
    trigger.onMatchResult(eventId, resolution, event.getSettledAt());
    ack.acknowledge();
    log.debug("Consumed match.result event={} status={}", eventId, event.getFinalStatus());
  }

  private static MatchOutcomeMode mode(MatchFinalStatus status) {
    return switch (status) {
      case COMPLETED -> MatchOutcomeMode.COMPLETED;
      case ABANDONED -> MatchOutcomeMode.ABANDONED;
      case VOIDED -> MatchOutcomeMode.VOIDED;
    };
  }

  private static Map<UUID, SettlementResult> outcomes(MatchResult event) {
    Map<UUID, SettlementResult> outcomes = new HashMap<>();
    event
        .getResultDetail()
        .forEach(
            (selectionId, result) ->
                outcomes.put(
                    UUID.fromString(selectionId.toString()),
                    SettlementResult.valueOf(result.toString())));
    return outcomes;
  }
}
