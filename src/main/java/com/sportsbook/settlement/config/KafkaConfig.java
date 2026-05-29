package com.sportsbook.settlement.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Producer factory for the outbox publisher. The wire shape is {@code (String key, byte[] value)}
 * because the outbox row carries the Avro-encoded payload as raw bytes — V1 publishes without a
 * Schema Registry (ADR-0014), each consumer pins the same shared-protocol Avro classes.
 *
 * <p>Idempotent producer is on so retries inside the broker do not duplicate records; combined with
 * the outbox's "retry until acked" loop, end-to-end delivery is at-least-once with partition-level
 * ordering preserved (partition key = eventId, ADR-0006).
 *
 * <p>The consumer side uses Spring Boot's auto-configured {@code kafkaListenerContainerFactory}
 * (byte[] value deserializer + manual ack from application.yml); the {@link DefaultErrorHandler}
 * bean below is auto-applied to it for retry + DLQ.
 */
@Configuration
public class KafkaConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

  // Confluent-recommended ceiling with enable.idempotence=true.
  private static final int MAX_IN_FLIGHT_REQUESTS = 5;
  static final String DLQ_METRIC = "settlement.dlq";

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Bean
  public ProducerFactory<String, byte[]> settlementProducerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, MAX_IN_FLIGHT_REQUESTS);
    props.put(ProducerConfig.CLIENT_ID_CONFIG, "settlement-service-outbox");
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, byte[]> settlementKafkaTemplate(
      ProducerFactory<String, byte[]> settlementProducerFactory) {
    return new KafkaTemplate<>(settlementProducerFactory);
  }

  /**
   * Retry + dead-letter handling for all settlement consumers (ADR-0006). A failed record is
   * retried with a fixed backoff up to {@code settlement.retry.max-attempts} total tries, then
   * republished to {@code <topic>.DLT} (same key/partition) via the byte[] template and counted on
   * the {@value #DLQ_METRIC} meter for ops alerting. Parse failures (bad UUID / unknown result in
   * resultDetail) are poison pills — non-retryable, so they go straight to the DLT. Spring Boot
   * wires this single {@link DefaultErrorHandler} bean into the auto-configured listener container
   * factory.
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, byte[]> settlementKafkaTemplate,
      MeterRegistry meterRegistry,
      @Value("${settlement.retry.max-attempts:3}") int maxAttempts,
      @Value("${settlement.retry.backoff-ms:500}") long backoffMs) {
    DeadLetterPublishingRecoverer dlq = new DeadLetterPublishingRecoverer(settlementKafkaTemplate);
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            (record, exception) -> {
              meterRegistry.counter(DLQ_METRIC, "topic", record.topic()).increment();
              log.error(
                  "Routing record from {} to DLT after retries: {}",
                  record.topic(),
                  exception.getMessage());
              dlq.accept(record, exception);
            },
            new FixedBackOff(backoffMs, (long) maxAttempts - 1));
    handler.addNotRetryableExceptions(IllegalArgumentException.class);
    return handler;
  }
}
