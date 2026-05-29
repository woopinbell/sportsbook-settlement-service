package com.sportsbook.settlement.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

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
 * (byte[] value deserializer + manual ack from application.yml); a custom DefaultErrorHandler for
 * the DLQ is layered on in the failure-handling step.
 */
@Configuration
public class KafkaConfig {

  // Confluent-recommended ceiling with enable.idempotence=true.
  private static final int MAX_IN_FLIGHT_REQUESTS = 5;

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
}
