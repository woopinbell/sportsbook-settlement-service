package com.sportsbook.settlement.event;

import com.sportsbook.protocol.event.EventLifecycle;
import com.sportsbook.protocol.event.VoidReason;
import com.sportsbook.settlement.service.SettlementTrigger;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code event.lifecycle} (odds-feed-service). Only CANCELLED / POSTPONED matter to
 * settlement: both void the affected bets and refund the stake (ADR-0012). FINISHED is
 * informational — settlement is driven by the richer MatchResult, not the lifecycle phase — and
 * SCHEDULED / IN_PLAY are ignored.
 */
@Component
public class EventLifecycleConsumer {

  private static final Logger log = LoggerFactory.getLogger(EventLifecycleConsumer.class);

  private final SettlementTrigger trigger;

  public EventLifecycleConsumer(SettlementTrigger trigger) {
    this.trigger = trigger;
  }

  @KafkaListener(
      topics = "${settlement.topics.event-lifecycle}",
      groupId = "settlement.event-lifecycle")
  public void onLifecycle(@Payload byte[] payload, Acknowledgment ack) {
    EventLifecycle event = AvroCodec.decode(payload, EventLifecycle.class);
    UUID eventId = UUID.fromString(event.getEventId().toString());
    switch (event.getStatus()) {
      case CANCELLED -> trigger.onEventCancelled(eventId, VoidReason.EVENT_CANCELLED);
      case POSTPONED -> trigger.onEventCancelled(eventId, VoidReason.EVENT_POSTPONED);
      default -> log.trace("Ignoring lifecycle {} for event {}", event.getStatus(), eventId);
    }
    ack.acknowledge();
  }
}
