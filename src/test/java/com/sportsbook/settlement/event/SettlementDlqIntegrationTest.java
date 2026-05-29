package com.sportsbook.settlement.event;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.protocol.event.MatchFinalStatus;
import com.sportsbook.protocol.event.MatchResult;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.domain.BetSelection;
import com.sportsbook.settlement.domain.SettlementStatus;
import com.sportsbook.settlement.domain.SlipKind;
import com.sportsbook.settlement.outbox.AvroSerializer;
import com.sportsbook.settlement.persistence.BetRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
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
 * Proves the DLQ path (ADR-0006): when settlement keeps failing (wallet down), the MatchResult is
 * retried the configured number of times and then republished to {@code <topic>.DLT} for admin
 * replay, leaving the bet PENDING rather than half-settled.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
    partitions = 1,
    topics = {"match.result", "match.result.DLT", "event.lifecycle", "bet.placed.v1"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("test")
class SettlementDlqIntegrationTest {

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
    // Keep the retry backoff short so the test reaches the DLT quickly.
    registry.add("settlement.retry.backoff-ms", () -> "200");
  }

  @AfterAll
  static void stopWireMock() {
    wm.stop();
  }

  @Autowired BetRepository bets;
  @Autowired EmbeddedKafkaBroker broker;

  private Producer<String, byte[]> producer;
  private Consumer<String, byte[]> dltConsumer;

  @AfterEach
  void cleanup() {
    if (producer != null) {
      producer.close();
    }
    if (dltConsumer != null) {
      dltConsumer.close();
    }
    bets.deleteAll();
  }

  @Test
  @DisplayName("a settlement that keeps failing lands on match.result.DLT")
  void failedSettlementRoutesToDlt() throws Exception {
    // Wallet is down for the whole test -> every credit attempt fails.
    wm.stubFor(
        post(urlEqualTo("/internal/v1/wallet/transactions/credit"))
            .willReturn(aResponse().withStatus(503)));

    UUID betId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID selectionId = UUID.randomUUID();
    bets.save(
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
            Instant.parse("2026-05-29T07:00:00Z")));

    dltConsumer = newConsumer();
    dltConsumer.subscribe(List.of("match.result.DLT"));

    MatchResult result =
        new MatchResult(
            eventId.toString(),
            "2-1",
            MatchFinalStatus.COMPLETED,
            Map.of(selectionId.toString(), "WON"),
            Instant.parse("2026-05-29T09:00:00Z"));
    send(eventId.toString(), AvroSerializer.serialize(result));

    var records = KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(20), 1);
    assertThat(records.count()).isEqualTo(1);
    // The bet was never half-settled — it stays PENDING for the admin replay.
    assertThat(bets.findById(betId).orElseThrow().status()).isEqualTo(SettlementStatus.PENDING);
  }

  private void send(String key, byte[] value) throws Exception {
    Map<String, Object> props = KafkaTestUtils.producerProps(broker);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    producer = new KafkaProducer<>(props);
    producer.send(new ProducerRecord<>("match.result", key, value)).get();
  }

  private Consumer<String, byte[]> newConsumer() {
    Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-test", "true", broker);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new KafkaConsumer<>(props);
  }
}
