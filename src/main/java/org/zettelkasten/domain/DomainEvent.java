package org.zettelkasten.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stored event envelope. Payload JSON is retained verbatim for forward compatibility. */
public record DomainEvent(
    long sequence,
    UUID eventId,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    UUID correlationId,
    String payloadJson) {
  public DomainEvent {
    if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(eventType, "eventType");
    if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(payloadJson, "payloadJson");
  }
}
