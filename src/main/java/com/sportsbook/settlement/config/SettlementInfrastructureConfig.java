package com.sportsbook.settlement.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared infrastructure beans. */
@Configuration
public class SettlementInfrastructureConfig {

  /**
   * A UTC {@link Clock} so every {@code Instant} the service stamps is timezone-safe (ADR-0003) and
   * tests can swap in a fixed clock.
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Programmatic transaction boundary for the settlement service's two-phase flow (prepare under a
   * row lock → wallet credit outside any transaction → finalize), keeping the synchronous wallet
   * HTTP call out of an open DB transaction.
   */
  @Bean
  TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }
}
