package org.zettelkasten.infrastructure.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.zettelkasten.application.PersistenceException;
import org.zettelkasten.application.port.AuthoredReferenceRepository;
import org.zettelkasten.domain.AuthoredReference;
import org.zettelkasten.domain.ReferenceKind;
import org.zettelkasten.domain.ResolutionStatus;

/** JDBC adapter for authored reference occurrences. */
final class SqliteAuthoredReferenceRepository implements AuthoredReferenceRepository {
  private final Connection connection;
  private final SqliteEventJournal events;

  SqliteAuthoredReferenceRepository(Connection connection, SqliteEventJournal events) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.events = Objects.requireNonNull(events, "events");
  }

  @Override
  public void insert(AuthoredReference reference) {
    Objects.requireNonNull(reference, "reference");
    String sql =
        "INSERT INTO refs(id,source_card_id,source_run_id,kind,authored_literal,target_literal,"
            + "target_card_id,target_source_id,target_index_id,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, reference.id().toString());
      insert.setString(2, reference.sourceCardId().toString());
      insert.setString(3, reference.sourceRunId().toString());
      insert.setString(4, reference.kind().name());
      insert.setString(5, reference.authoredLiteral());
      insert.setString(6, reference.targetLiteral());
      insert.setString(7, value(reference.targetCardId()));
      insert.setString(8, value(reference.targetSourceId()));
      insert.setString(9, value(reference.targetIndexId()));
      insert.setString(10, reference.status().name());
      insert.setString(11, reference.createdAt().toString());
      insert.executeUpdate();
      events.append(
          "ReferenceAuthored",
          "MAIN_CARD",
          reference.sourceCardId(),
          "{\"referenceId\":\""
              + reference.id()
              + "\",\"kind\":\""
              + reference.kind().name()
              + "\"}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not save authored reference", exception);
    }
  }

  @Override
  public List<AuthoredReference> findBySourceCard(UUID sourceCardId) {
    Objects.requireNonNull(sourceCardId, "sourceCardId");
    return readReferences(
        "SELECT id,source_card_id,source_run_id,kind,authored_literal,target_literal,target_card_id,"
            + "target_source_id,target_index_id,status,created_at FROM refs WHERE source_card_id=? ORDER BY sequence",
        sourceCardId);
  }

  @Override
  public List<AuthoredReference> findAll() {
    return readReferences(
        "SELECT id,source_card_id,source_run_id,kind,authored_literal,target_literal,target_card_id,"
            + "target_source_id,target_index_id,status,created_at FROM refs ORDER BY sequence",
        null);
  }

  private List<AuthoredReference> readReferences(String sql, UUID sourceCardId) {
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      if (sourceCardId != null) query.setString(1, sourceCardId.toString());
      try (ResultSet rows = query.executeQuery()) {
        List<AuthoredReference> references = new ArrayList<>();
        while (rows.next()) {
          references.add(
              new AuthoredReference(
                  UUID.fromString(rows.getString(1)),
                  UUID.fromString(rows.getString(2)),
                  UUID.fromString(rows.getString(3)),
                  ReferenceKind.valueOf(rows.getString(4)),
                  rows.getString(5),
                  rows.getString(6),
                  uuid(rows.getString(7)),
                  uuid(rows.getString(8)),
                  uuid(rows.getString(9)),
                  ResolutionStatus.valueOf(rows.getString(10)),
                  Instant.parse(rows.getString(11))));
        }
        return List.copyOf(references);
      }
    } catch (SQLException | RuntimeException exception) {
      throw new PersistenceException("Could not read authored references", exception);
    }
  }

  @Override
  public void updateResolution(AuthoredReference reference) {
    Objects.requireNonNull(reference, "reference");
    if (reference.status() != ResolutionStatus.RESOLVED
        && reference.status() != ResolutionStatus.AMBIGUOUS)
      throw new IllegalArgumentException(
          "Resolution updates must resolve or disambiguate a target");
    String sql =
        "UPDATE refs SET target_card_id=?,target_source_id=?,target_index_id=?,status=? "
            + "WHERE id=? AND status='PENDING'";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setString(1, value(reference.targetCardId()));
      update.setString(2, value(reference.targetSourceId()));
      update.setString(3, value(reference.targetIndexId()));
      update.setString(4, reference.status().name());
      update.setString(5, reference.id().toString());
      if (update.executeUpdate() != 1)
        throw new PersistenceException("Reference is no longer pending: " + reference.id());
      events.append(
          "ReferenceResolutionChanged",
          "MAIN_CARD",
          reference.sourceCardId(),
          "{\"referenceId\":\""
              + reference.id()
              + "\",\"status\":\""
              + reference.status().name()
              + "\",\"targetCardId\":"
              + nullableJson(reference.targetCardId())
              + ",\"targetSourceId\":"
              + nullableJson(reference.targetSourceId())
              + ",\"targetIndexId\":"
              + nullableJson(reference.targetIndexId())
              + "}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not update authored reference resolution", exception);
    }
  }

  private static UUID uuid(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  private static String value(UUID value) {
    return value == null ? null : value.toString();
  }

  private static String nullableJson(UUID value) {
    return value == null ? "null" : "\"" + value + "\"";
  }
}
