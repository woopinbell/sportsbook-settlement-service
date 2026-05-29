package com.sportsbook.settlement.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.event.BetSlipTypeTag;
import com.sportsbook.protocol.event.Money;
import com.sportsbook.protocol.event.RequestedSelection;
import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.domain.SettlementStatus;
import com.sportsbook.settlement.domain.SlipKind;
import com.sportsbook.settlement.persistence.BetRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
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
 * Proves the event-sourced read model: a {@code BetPlacedRequested} on {@code bet.placed.v1}
 * becomes a PENDING bet row with its selections, and a re-delivery of the same event is an
 * idempotent no-op (ADR-0006).
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
    partitions = 1,
    topics = {"bet.placed.v1"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("test")
class BetPlacedConsumerIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired BetRepository bets;
  @Autowired EmbeddedKafkaBroker broker;

  private Producer<String, byte[]> producer;

  @AfterEach
  void cleanup() {
    if (producer != null) {
      producer.close();
    }
    bets.deleteAll();
  }

  @Test
  @DisplayName("consuming BetPlacedRequested creates a PENDING bet with its selections")
  void buildsReadModel() throws Exception {
    UUID betId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BetPlacedRequested event = multipleBet(betId, userId);

    send(userId.toString(), AvroCodec.encode(event));

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              Bet bet = bets.findWithSelectionsById(betId).orElse(null);
              assertThat(bet).isNotNull();
              assertThat(bet.userId()).isEqualTo(userId);
              assertThat(bet.slipType()).isEqualTo(SlipKind.MULTIPLE);
              assertThat(bet.status()).isEqualTo(SettlementStatus.PENDING);
              assertThat(bet.stake()).isEqualTo(com.sportsbook.protocol.value.Money.krw(10_000));
              assertThat(bet.selections()).hasSize(2);
            });
  }

  @Test
  @DisplayName("re-delivering the same event is idempotent (no duplicate row)")
  void idempotentReplay() throws Exception {
    UUID betId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    byte[] payload = AvroCodec.encode(multipleBet(betId, userId));

    send(userId.toString(), payload);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(bets.findById(betId)).isPresent());

    send(userId.toString(), payload);

    // The second delivery must never add a row; assert the count stays 1 throughout a window.
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(bets.count()).isEqualTo(1L);
              assertThat(bets.findWithSelectionsById(betId).orElseThrow().selections()).hasSize(2);
            });
  }

  private void send(String key, byte[] value) throws Exception {
    if (producer == null) {
      Map<String, Object> props = KafkaTestUtils.producerProps(broker);
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
      producer = new KafkaProducer<>(props);
    }
    producer.send(new ProducerRecord<>("bet.placed.v1", key, value)).get();
  }

  private static BetPlacedRequested multipleBet(UUID betId, UUID userId) {
    return BetPlacedRequested.newBuilder()
        .setBetId(betId.toString())
        .setUserId(userId.toString())
        .setSlipType(BetSlipTypeTag.MULTIPLE)
        .setSystemMinWins(null)
        .setSystemTotalSelections(null)
        .setSelections(List.of(selection("1.8500"), selection("2.1000")))
        .setStake(Money.newBuilder().setAmount(10_000L).setCurrency("KRW").build())
        .setIdempotencyKey("idem-" + betId)
        .setRequestedAt(Instant.parse("2026-05-29T07:00:00Z"))
        .build();
  }

  private static RequestedSelection selection(String odds) {
    return RequestedSelection.newBuilder()
        .setEventId(UUID.randomUUID().toString())
        .setMarketId(UUID.randomUUID().toString())
        .setSelectionId(UUID.randomUUID().toString())
        .setOddsAtSubmission(odds)
        .build();
  }
}
