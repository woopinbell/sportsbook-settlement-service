package com.sportsbook.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Settlement service entry point.
 *
 * <p>Owns bet settlement (ADR-0006 async Saga + Outbox). It event-sources a read model from {@code
 * BetPlacedRequested}, then on a {@code MatchResult} resolves every affected bet — Single /
 * Multiple / System(K-of-N) — to WON / LOST / PUSH / VOID (ADR-0008 / ADR-0013), credits the wallet
 * for winnings and refunds, and publishes {@code BetSettled} / {@code BetVoided} through a
 * transactional outbox. Cancelled / postponed events void the bet and refund the stake (ADR-0012).
 *
 * <p>Scheduling is enabled application-wide so the outbox publisher and the late-settlement window
 * jobs can declare {@code @Scheduled} hooks without per-feature plumbing.
 *
 * <p>{@code @ConfigurationPropertiesScan} binds {@code settlement.*} into the typed properties
 * records (topics, wallet client, retry / DLQ, late-settlement window) without an explicit
 * {@code @EnableConfigurationProperties}.
 */
// @SpringBootApplication is meta-annotated with @Configuration, so Spring instantiates this class
// as a bean; a private constructor would break that. Suppress the utility-class rule explicitly.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SettlementServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(SettlementServiceApplication.class, args);
  }
}
