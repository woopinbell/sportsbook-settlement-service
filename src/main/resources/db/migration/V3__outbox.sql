-- V3: transactional outbox table (ADR-0006 Saga + Outbox).
--
-- The settlement flow writes a BetSettled / BetVoided row here in the SAME DB
-- transaction that flips the bet to SETTLED / VOIDED, so the event can neither
-- vanish (rolled back with the transition) nor be emitted for a bet that was
-- never settled. A scheduled publisher drains unpublished rows to Kafka and
-- stamps published_at on ack.

CREATE TABLE outbox_event (
    event_id      UUID                     PRIMARY KEY,
    topic         VARCHAR(64)              NOT NULL,
    partition_key VARCHAR(64)              NOT NULL,
    schema_name   VARCHAR(64)              NOT NULL,
    payload       BYTEA                    NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at  TIMESTAMP WITH TIME ZONE
);

COMMENT ON TABLE  outbox_event               IS 'Transactional outbox — rows are published to Kafka by OutboxPublisher.';
COMMENT ON COLUMN outbox_event.partition_key IS 'Kafka partition key = eventId (ADR-0006: a match''s settlement events share a partition, preserving order).';
COMMENT ON COLUMN outbox_event.schema_name   IS 'Avro record name (BetSettled / BetVoided) for diagnostics.';
COMMENT ON COLUMN outbox_event.payload       IS 'Avro binary-encoded record bytes (no Schema Registry in V1, ADR-0014).';
COMMENT ON COLUMN outbox_event.published_at  IS 'Set when the publisher receives a Kafka ack; NULL means still pending.';

-- Hot read path: "unpublished rows oldest first". Partial index on the rare
-- NULL state keeps the scan small as the table grows.
CREATE INDEX ix_outbox_unpublished
    ON outbox_event (created_at)
    WHERE published_at IS NULL;
