package com.sportsbook.settlement.event;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.protocol.event.EventLifecycle;
import com.sportsbook.protocol.event.EventLifecycleStatus;
import com.sportsbook.protocol.event.MatchFinalStatus;
import com.sportsbook.protocol.event.MatchResult;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.domain.BetSelection;
import com.sportsbook.settlement.domain.SettlementStatus;
import com.sportsbook.settlement.domain.SlipKind;
import com.sportsbook.settlement.persistence.BetRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end trigger wiring: a MatchResult on {@code match.result} settles the bets on that event,
 * and a CANCELLED EventLifecycle on {@code event.lifecycle} voids them (ADR-0012). FINISHED is
 * informational and leaves bets PENDING.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
    partitions = 1,
    topics = {"match.result", "event.lifecycle", "bet.placed.v1"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("test")
class SettlementTriggerIntegrationTest {

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

  @Autowired BetRepository bets;
  @Autowired EmbeddedKafkaBroker broker;

  private Producer<String, byte[]> producer;

  @BeforeEach
  void setUp() {
    wm.resetAll();
    wm.stubFor(
        post(urlEqualTo("/internal/v1/wallet/transactions/credit"))
            .willReturn(okJson("{\"operationGroupId\":\"" + UUID.randomUUID() + "\"}")));
  }

  @AfterEach
  void cleanup() {
    if (producer != null) {
      producer.close();
    }
    bets.deleteAll();
  }

  @Test
  @DisplayName("MatchResult settles the pending bets on its event")
  void matchResultSettles() {
    UUID betId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID selectionId = UUID.randomUUID();
    savePendingSingle(betId, eventId, selectionId);

    MatchResult result =
        new MatchResult(
            eventId.toString(),
            "2-1",
            MatchFinalStatus.COMPLETED,
            Map.of(selectionId.toString(), "WON"),
            Instant.parse("2026-05-29T09:00:00Z"));
    send("match.result", eventId.toString(), result);

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              Bet bet = bets.findById(betId).orElseThrow();
              assertThat(bet.status()).isEqualTo(SettlementStatus.SETTLED);
              assertThat(bet.payout()).isEqualTo(Money.krw(20_000)); // 10000 * 2.0
            });
  }

  @Test
  @DisplayName("CANCELLED EventLifecycle voids the pending bets on its event")
  void cancelledLifecycleVoids() {
    UUID betId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    savePendingSingle(betId, eventId, UUID.randomUUID());

    EventLifecycle lifecycle =
        new EventLifecycle(
            eventId.toString(),
            EventLifecycleStatus.CANCELLED,
            Instant.parse("2026-05-29T09:00:00Z"),
            Instant.parse("2026-05-29T08:00:00Z"));
    send("event.lifecycle", eventId.toString(), lifecycle);

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(bets.findById(betId).orElseThrow().status())
                    .isEqualTo(SettlementStatus.VOIDED));
  }

  @Test
  @DisplayName("FINISHED EventLifecycle is informational and leaves the bet PENDING")
  void finishedLifecycleIsNoOp() {
    UUID betId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    savePendingSingle(betId, eventId, UUID.randomUUID());

    EventLifecycle lifecycle =
        new EventLifecycle(
            eventId.toString(),
            EventLifecycleStatus.FINISHED,
            Instant.parse("2026-05-29T09:00:00Z"),
            Instant.parse("2026-05-29T08:00:00Z"));
    send("event.lifecycle", eventId.toString(), lifecycle);

    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(bets.findById(betId).orElseThrow().status())
                    .isEqualTo(SettlementStatus.PENDING));
  }

  private void savePendingSingle(UUID betId, UUID eventId, UUID selectionId) {
    Bet bet =
        Bet.fromPlacement(
            betId,
            UUID.randomUUID(),
            SlipKind.SINGLE,
            null,
            null,
            Money.krw(10_000),
            Instant.parse("2026-05-29T07:00:00Z"),
            List.of(
                BetSelection.create(
                    eventId, UUID.randomUUID(), selectionId, new BigDecimal("2.0000"))),
            Instant.parse("2026-05-29T07:00:00Z"));
    bets.save(bet);
  }

  private void send(String topic, String key, SpecificRecord record) {
    if (producer == null) {
      Map<String, Object> props = KafkaTestUtils.producerProps(broker);
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
      producer = new KafkaProducer<>(props);
    }
    try {
      producer
          .send(
              new ProducerRecord<>(
                  topic, key, com.sportsbook.settlement.outbox.AvroSerializer.serialize(record)))
          .get();
    } catch (Exception e) {
      throw new IllegalStateException("failed to publish test record", e);
    }
  }
}
