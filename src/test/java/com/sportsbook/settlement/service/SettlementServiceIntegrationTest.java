package com.sportsbook.settlement.service;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.event.BetSettled;
import com.sportsbook.protocol.event.BetVoided;
import com.sportsbook.protocol.event.SettlementResultAvro;
import com.sportsbook.protocol.event.VoidReason;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.domain.BetSelection;
import com.sportsbook.settlement.domain.SettlementStatus;
import com.sportsbook.settlement.domain.SlipKind;
import com.sportsbook.settlement.outbox.AvroSerializer;
import com.sportsbook.settlement.outbox.OutboxEvent;
import com.sportsbook.settlement.outbox.OutboxEventRepository;
import com.sportsbook.settlement.persistence.BetRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Proves the wallet-facing settlement flow against a WireMock wallet + real PostgreSQL: WON pays
 * the two legs (refund LOCKED stake + HOUSE profit), LOST credits nothing, settle-VOID and
 * event-void refund the stake, a multi only settles once all its events resolve, and a redelivered
 * settle is an idempotent no-op (ADR-0006).
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("test")
class SettlementServiceIntegrationTest {

  private static final String CREDIT_PATH = "/internal/v1/wallet/transactions/credit";

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

  @BeforeEach
  void stubWallet() {
    wm.resetAll();
    wm.stubFor(
        post(urlEqualTo(CREDIT_PATH))
            .willReturn(okJson("{\"operationGroupId\":\"" + UUID.randomUUID() + "\"}")));
  }

  @AfterEach
  void cleanup() {
    outbox.deleteAll();
    bets.deleteAll();
  }

  @Test
  @DisplayName("WON single pays both legs and publishes BetSettled; replay is a no-op")
  void wonSinglePaysTwoLegs() {
    UUID betId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID selectionId = UUID.randomUUID();
    saveSingle(betId, userId, eventId, selectionId, "2.0000", Money.krw(10_000));

    settlement.settleOnEvent(betId, eventId, completed(Map.of(selectionId, SettlementResult.WON)));

    Bet settled = bets.findById(betId).orElseThrow();
    assertThat(settled.status()).isEqualTo(SettlementStatus.SETTLED);
    assertThat(settled.result()).isEqualTo(SettlementResult.WON);
    assertThat(settled.payout()).isEqualTo(Money.krw(20_000));

    // Refund leg (LOCKED, full committed stake) + profit leg (HOUSE, payout - stake).
    wm.verify(
        postRequestedFor(urlEqualTo(CREDIT_PATH))
            .withHeader("Idempotency-Key", equalTo("settle:refund:" + betId))
            .withRequestBody(matchingJsonPath("$.source", equalTo("USER_LOCKED"))));
    wm.verify(
        postRequestedFor(urlEqualTo(CREDIT_PATH))
            .withHeader("Idempotency-Key", equalTo("settle:payout:" + betId))
            .withRequestBody(matchingJsonPath("$.source", equalTo("HOUSE_POOL"))));

    BetSettled event = onlySettled();
    assertThat(event.getResult()).isEqualTo(SettlementResultAvro.WON);
    assertThat(event.getPayout().getAmount()).isEqualTo(20_000L);
    assertThat(event.getStake().getAmount()).isEqualTo(10_000L);

    // Replay: same MatchResult again must not double-pay or double-publish.
    settlement.settleOnEvent(betId, eventId, completed(Map.of(selectionId, SettlementResult.WON)));
    wm.verify(exactly(2), postRequestedFor(urlEqualTo(CREDIT_PATH)));
    assertThat(outbox.count()).isEqualTo(1L);
  }

  @Test
  @DisplayName("LOST single credits nothing and publishes BetSettled(LOST)")
  void lostSingleCreditsNothing() {
    UUID betId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID selectionId = UUID.randomUUID();
    saveSingle(betId, UUID.randomUUID(), eventId, selectionId, "2.0000", Money.krw(10_000));

    settlement.settleOnEvent(betId, eventId, completed(Map.of(selectionId, SettlementResult.LOST)));

    Bet settled = bets.findById(betId).orElseThrow();
    assertThat(settled.status()).isEqualTo(SettlementStatus.SETTLED);
    assertThat(settled.result()).isEqualTo(SettlementResult.LOST);
    assertThat(settled.payout()).isEqualTo(Money.krw(0));
    wm.verify(exactly(0), postRequestedFor(urlEqualTo(CREDIT_PATH)));
    assertThat(onlySettled().getResult()).isEqualTo(SettlementResultAvro.LOST);
  }

  @Test
  @DisplayName("settle-time VOID refunds the stake from LOCKED")
  void voidSelectionRefundsStake() {
    UUID betId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID selectionId = UUID.randomUUID();
    saveSingle(betId, UUID.randomUUID(), eventId, selectionId, "2.0000", Money.krw(10_000));

    settlement.settleOnEvent(betId, eventId, completed(Map.of(selectionId, SettlementResult.VOID)));

    Bet settled = bets.findById(betId).orElseThrow();
    assertThat(settled.result()).isEqualTo(SettlementResult.VOID);
    assertThat(settled.payout()).isEqualTo(Money.krw(10_000));
    wm.verify(
        exactly(1),
        postRequestedFor(urlEqualTo(CREDIT_PATH))
            .withHeader("Idempotency-Key", equalTo("settle:refund:" + betId))
            .withRequestBody(matchingJsonPath("$.source", equalTo("USER_LOCKED"))));
  }

  @Test
  @DisplayName("voidOnEvent refunds the committed stake and publishes BetVoided")
  void eventVoidRefundsAndPublishesVoided() {
    UUID betId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    saveSingle(betId, userId, eventId, UUID.randomUUID(), "2.0000", Money.krw(10_000));

    settlement.voidOnEvent(betId, eventId, VoidReason.EVENT_CANCELLED);

    Bet voided = bets.findById(betId).orElseThrow();
    assertThat(voided.status()).isEqualTo(SettlementStatus.VOIDED);
    assertThat(voided.payout()).isEqualTo(Money.krw(10_000));
    wm.verify(
        exactly(1),
        postRequestedFor(urlEqualTo(CREDIT_PATH))
            .withHeader("Idempotency-Key", equalTo("void:refund:" + betId)));

    OutboxEvent row = outbox.findAll().get(0);
    assertThat(row.schemaName()).isEqualTo("BetVoided");
    BetVoided event = AvroSerializer.deserialize(row.payload(), BetVoided.class);
    assertThat(event.getReason()).isEqualTo(VoidReason.EVENT_CANCELLED);
    assertThat(event.getRefund().getAmount()).isEqualTo(10_000L);
  }

  @Test
  @DisplayName("a multi only settles once all its events have resolved")
  void multiWaitsForAllEvents() {
    UUID betId = UUID.randomUUID();
    UUID eventA = UUID.randomUUID();
    UUID eventB = UUID.randomUUID();
    UUID selA = UUID.randomUUID();
    UUID selB = UUID.randomUUID();
    Bet bet =
        Bet.fromPlacement(
            betId,
            UUID.randomUUID(),
            SlipKind.MULTIPLE,
            null,
            null,
            Money.krw(10_000),
            Instant.parse("2026-05-29T07:00:00Z"),
            List.of(
                BetSelection.create(eventA, UUID.randomUUID(), selA, new BigDecimal("2.0000")),
                BetSelection.create(eventB, UUID.randomUUID(), selB, new BigDecimal("1.5000"))),
            Instant.parse("2026-05-29T07:00:00Z"));
    bets.save(bet);

    // First event resolves -> not ready: no credit, no settlement, still PENDING.
    settlement.settleOnEvent(betId, eventA, completed(Map.of(selA, SettlementResult.WON)));
    assertThat(bets.findById(betId).orElseThrow().status()).isEqualTo(SettlementStatus.PENDING);
    wm.verify(exactly(0), postRequestedFor(urlEqualTo(CREDIT_PATH)));
    assertThat(outbox.count()).isZero();

    // Second event resolves -> settle WON, payout 10000 * 2.0 * 1.5 = 30000.
    settlement.settleOnEvent(betId, eventB, completed(Map.of(selB, SettlementResult.WON)));
    Bet settled = bets.findById(betId).orElseThrow();
    assertThat(settled.status()).isEqualTo(SettlementStatus.SETTLED);
    assertThat(settled.payout()).isEqualTo(Money.krw(30_000));
    assertThat(onlySettled().getPayout().getAmount()).isEqualTo(30_000L);
  }

  private static EventResolution completed(Map<UUID, SettlementResult> outcomes) {
    return new EventResolution(EventResolution.Mode.COMPLETED, outcomes);
  }

  private void saveSingle(
      UUID betId, UUID userId, UUID eventId, UUID selectionId, String odds, Money stake) {
    Bet bet =
        Bet.fromPlacement(
            betId,
            userId,
            SlipKind.SINGLE,
            null,
            null,
            stake,
            Instant.parse("2026-05-29T07:00:00Z"),
            List.of(
                BetSelection.create(eventId, UUID.randomUUID(), selectionId, new BigDecimal(odds))),
            Instant.parse("2026-05-29T07:00:00Z"));
    bets.save(bet);
  }

  private BetSettled onlySettled() {
    OutboxEvent row = outbox.findAll().get(0);
    assertThat(row.schemaName()).isEqualTo("BetSettled");
    return AvroSerializer.deserialize(row.payload(), BetSettled.class);
  }
}
