package com.sportsbook.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka topic names mirrored from {@code application.yml} ({@code settlement.topics.*}). Keeps the
 * constants out of code so orchestration can override topic prefixes per environment without
 * rebuilding the service.
 *
 * <p>Inbound: {@code betPlaced} (betting-service's actual publish topic {@code bet.placed.v1}),
 * {@code matchResult} / {@code eventLifecycle} (odds-feed-service). Outbound: {@code betSettled} /
 * {@code betVoided}.
 */
@ConfigurationProperties(prefix = "settlement.topics")
public record SettlementTopics(
    String betPlaced,
    String matchResult,
    String eventLifecycle,
    String betSettled,
    String betVoided) {

  public SettlementTopics {
    if (betPlaced == null || betPlaced.isBlank()) {
      betPlaced = "bet.placed.v1";
    }
    if (matchResult == null || matchResult.isBlank()) {
      matchResult = "match.result";
    }
    if (eventLifecycle == null || eventLifecycle.isBlank()) {
      eventLifecycle = "event.lifecycle";
    }
    if (betSettled == null || betSettled.isBlank()) {
      betSettled = "bet.settled";
    }
    if (betVoided == null || betVoided.isBlank()) {
      betVoided = "bet.voided";
    }
  }
}
