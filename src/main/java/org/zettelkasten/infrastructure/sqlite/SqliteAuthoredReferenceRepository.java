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
    String sql =
        "SELECT id,source_card_id,source_run_id,kind,authored_literal,target_literal,target_card_id,"
            + "target_source_id,target_index_id,status,created_at FROM refs WHERE source_card_id=? ORDER BY sequence";
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, sourceCardId.toString());
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

  private static UUID uuid(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  private static String value(UUID value) {
    return value == null ? null : value.toString();
  }
}
