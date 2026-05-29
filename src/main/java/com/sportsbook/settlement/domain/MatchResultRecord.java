package com.sportsbook.settlement.domain;

import com.sportsbook.protocol.domain.SettlementResult;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Settlement's materialized view of one event's result (ADR-0006). Stores the {@link
 * MatchOutcomeMode} and the per-selection outcomes a MatchResult delivered, so the event can be
 * replayed on demand and a late-arriving bet can be settled once its read model catches up. A
 * corrected result within the 24h window (ADR-0012) is an upsert on {@code eventId}.
 */
@Entity
@Table(name = "match_result")
public class MatchResultRecord {

  @Id
  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Enumerated(EnumType.STRING)
  @Column(name = "mode", nullable = false, length = 16)
  private MatchOutcomeMode mode;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "match_selection_result", joinColumns = @JoinColumn(name = "event_id"))
  @MapKeyColumn(name = "selection_id")
  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, length = 8)
  private Map<UUID, SettlementResult> selectionOutcomes = new HashMap<>();

  @Column(name = "settled_at", nullable = false)
  private Instant settledAt;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  protected MatchResultRecord() {
    // Required by JPA.
  }

  private MatchResultRecord(
      UUID eventId,
      MatchOutcomeMode mode,
      Map<UUID, SettlementResult> selectionOutcomes,
      Instant settledAt,
      Instant receivedAt) {
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.selectionOutcomes = new HashMap<>(Objects.requireNonNull(selectionOutcomes, "outcomes"));
    this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
    this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
  }

  public static MatchResultRecord of(
      UUID eventId,
      MatchOutcomeMode mode,
      Map<UUID, SettlementResult> selectionOutcomes,
      Instant settledAt,
      Instant receivedAt) {
    return new MatchResultRecord(eventId, mode, selectionOutcomes, settledAt, receivedAt);
  }

  public UUID eventId() {
    return eventId;
  }

  public MatchOutcomeMode mode() {
    return mode;
  }

  public Map<UUID, SettlementResult> selectionOutcomes() {
    return Map.copyOf(selectionOutcomes);
  }

  public Instant settledAt() {
    return settledAt;
  }
}
