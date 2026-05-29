package com.sportsbook.settlement.load;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.domain.BetSelection;
import com.sportsbook.settlement.domain.MatchOutcomeMode;
import com.sportsbook.settlement.domain.SlipKind;
import com.sportsbook.settlement.outbox.OutboxEventRepository;
import com.sportsbook.settlement.persistence.BetRepository;
import com.sportsbook.settlement.service.EventResolution;
import com.sportsbook.settlement.service.SettlementService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Throughput harness for the batch-settlement target (sportsbook/CLAUDE.md: 10k bets &lt; 10s).
 * Settlement ingests over Kafka, so this drives the settle engine directly — seed N PENDING bets,
 * then settle them in parallel and time the wall clock — isolating the resolve + wallet-credit +
 * persist + outbox pipeline from the broker (which scales independently with partitions). Tagged
 * {@code load} so it is skipped in the normal build; run with
 *
 * <pre>mvn test -DexcludedGroups= -Dtest=SettlementThroughputLoadTest \
 *   -Dsettlement.load.bets=10000 -Dsettlement.load.threads=32</pre>
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("test")
@Tag("load")
class SettlementThroughputLoadTest {

  private static final Logger log = LoggerFactory.getLogger(SettlementThroughputLoadTest.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  static WireMockServer wm = new WireMockServer(options().dynamicPort());

  static {
    wm.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("settlement.wallet.base-url", wm::baseUrl);
  }

  @AfterAll
  static void stopWireMock() {
    wm.stop();
  }

  @Autowired SettlementService settlement;
  @Autowired BetRepository bets;
  @Autowired OutboxEventRepository outbox;

  @Test
  @DisplayName("settles a batch of WON bets and reports throughput")
  void settleBatch() throws Exception {
    int total = Integer.getInteger("settlement.load.bets", 2_000);
    int threads = Integer.getInteger("settlement.load.threads", 32);
    // WON exercises the full payout path (2 wallet credits/bet); LOST isolates the settle engine.
    SettlementResult outcome =
        SettlementResult.valueOf(System.getProperty("settlement.load.outcome", "WON"));
    wm.stubFor(
        post(urlEqualTo("/internal/v1/wallet/transactions/credit"))
            .willReturn(okJson("{\"operationGroupId\":\"" + UUID.randomUUID() + "\"}")));

    List<UUID[]> work = seed(total); // [betId, eventId, selectionId]

    long startNanos = System.nanoTime();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (UUID[] w : work) {
        pool.submit(
            () -> {
              settlement.settleOnEvent(
                  w[0],
                  w[1],
                  new EventResolution(MatchOutcomeMode.COMPLETED, Map.of(w[2], outcome)));
              return null;
            });
      }
      pool.shutdown();
      assertThat(pool.awaitTermination(5, TimeUnit.MINUTES)).isTrue();
    } finally {
      pool.shutdownNow();
    }
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

    long settled = outbox.count();
    double perSecond = total / (elapsed.toMillis() / 1000.0);
    log.info(
        "SETTLEMENT THROUGHPUT: settled={} in {} ms => {} bets/s ({} threads)",
        settled,
        elapsed.toMillis(),
        String.format("%.0f", perSecond),
        threads);

    assertThat(settled).isEqualTo((long) total); // every bet settled exactly once (one outbox row)
  }

  private List<UUID[]> seed(int total) {
    List<Bet> batch = new ArrayList<>();
    List<UUID[]> work = new ArrayList<>(total);
    Instant now = Instant.parse("2026-05-29T07:00:00Z");
    for (int i = 0; i < total; i++) {
      UUID betId = UUID.randomUUID();
      UUID eventId = UUID.randomUUID();
      UUID selectionId = UUID.randomUUID();
      batch.add(
          Bet.fromPlacement(
              betId,
              UUID.randomUUID(),
              SlipKind.SINGLE,
              null,
              null,
              Money.krw(10_000),
              now,
              List.of(
                  BetSelection.create(
                      eventId, UUID.randomUUID(), selectionId, new BigDecimal("2.0000"))),
              now));
      work.add(new UUID[] {betId, eventId, selectionId});
      if (batch.size() == 1_000) {
        bets.saveAll(batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      bets.saveAll(batch);
    }
    return work;
  }
}
