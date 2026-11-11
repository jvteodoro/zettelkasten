package org.zettelkasten.infrastructure.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.zettelkasten.application.PersistenceException;
import org.zettelkasten.application.port.DomainEventRepository;
import org.zettelkasten.domain.DomainEvent;

/** Appends minimal event envelopes and reads raw, versioned journal entries. */
final class SqliteEventJournal implements DomainEventRepository {
  private final Connection connection;
  private final Clock clock;
  private final Supplier<UUID> identities;

  SqliteEventJournal(Connection connection, Clock clock, Supplier<UUID> identities) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.identities = Objects.requireNonNull(identities, "identities");
  }

  @Override
  public void append(String type, String aggregateType, UUID aggregateId, String payloadJson) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(payloadJson, "payloadJson");
    String sql =
        "INSERT INTO domain_events(event_id,aggregate_type,aggregate_id,event_type,schema_version,"
            + "happened_at,correlation_id,payload_json) VALUES(?,?,?,?,1,?,?,?)";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, identities.get().toString());
      insert.setString(2, aggregateType);
      insert.setString(3, aggregateId.toString());
      insert.setString(4, type);
      insert.setString(5, DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock)));
      insert.setString(6, identities.get().toString());
      insert.setString(7, payloadJson);
      insert.executeUpdate();
    } catch (SQLException exception) {
      throw new PersistenceException("Could not append domain event", exception);
    }
  }

  @Override
  public List<DomainEvent> findAfter(long sequenceExclusive, int limit) {
    if (sequenceExclusive < 0 || limit < 1) throw new IllegalArgumentException("invalid page");
    String sql =
        "SELECT global_seq,event_id,aggregate_type,aggregate_id,event_type,schema_version,happened_at,"
            + "correlation_id,payload_json FROM domain_events WHERE global_seq>? ORDER BY global_seq LIMIT ?";
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      query.setLong(1, sequenceExclusive);
      query.setInt(2, limit);
      try (ResultSet rows = query.executeQuery()) {
        List<DomainEvent> events = new ArrayList<>();
        while (rows.next()) {
          events.add(
              new DomainEvent(
                  rows.getLong(1),
                  UUID.fromString(rows.getString(2)),
                  rows.getString(3),
                  UUID.fromString(rows.getString(4)),
                  rows.getString(5),
                  rows.getInt(6),
                  Instant.parse(rows.getString(7)),
                  UUID.fromString(rows.getString(8)),
                  rows.getString(9)));
        }
        return List.copyOf(events);
      }
    } catch (SQLException | RuntimeException exception) {
      throw new PersistenceException("Could not read domain event journal", exception);
    }
  }
}
