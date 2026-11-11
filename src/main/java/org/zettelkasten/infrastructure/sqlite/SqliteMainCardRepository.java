package org.zettelkasten.infrastructure.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.zettelkasten.application.AddressConflictException;
import org.zettelkasten.application.PersistenceException;
import org.zettelkasten.application.port.MainCardRepository;
import org.zettelkasten.domain.BodyRun;
import org.zettelkasten.domain.CardAnnotation;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.CardState;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.domain.KeytermLinkRun;
import org.zettelkasten.domain.MainCard;
import org.zettelkasten.domain.NumericAlphaAddress;
import org.zettelkasten.domain.PlacementSnapshot;
import org.zettelkasten.domain.TextRun;

/** JDBC adapter for MainCardRepository. All mutations participate in the caller's transaction. */
final class SqliteMainCardRepository implements MainCardRepository {
  private final Connection connection;
  private final SqliteEventJournal events;

  SqliteMainCardRepository(Connection connection, SqliteEventJournal events) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.events = Objects.requireNonNull(events, "events");
  }

  @Override
  public Optional<MainCard> findById(UUID id) {
    Objects.requireNonNull(id, "id");
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT id, created_at, state, revision, installed_at, installed_body_revision "
                + "FROM cards WHERE id=? AND kind='MAIN'")) {
      query.setString(1, id.toString());
      try (ResultSet row = query.executeQuery()) {
        return row.next() ? Optional.of(readCard(row)) : Optional.empty();
      }
    } catch (SQLException | RuntimeException exception) {
      throw new PersistenceException("Could not read Main card", exception);
    }
  }

  @Override
  public Optional<MainCard> findByAddress(NumericAlphaAddress address) {
    Objects.requireNonNull(address, "address");
    try (PreparedStatement query =
        connection.prepareStatement("SELECT card_id FROM placements WHERE canonical_address=?")) {
      query.setString(1, address.canonical());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return findById(UUID.fromString(row.getString(1)));
      }
    } catch (SQLException | RuntimeException exception) {
      throw new PersistenceException("Could not read Main card by exact address", exception);
    }
  }

  @Override
  public List<MainCard> findDrafts() {
    return list(
        "SELECT id FROM cards WHERE kind='MAIN' AND state='DRAFT' ORDER BY created_at,id", null);
  }

  @Override
  public List<MainCard> findInstalledInAddressOrder() {
    List<MainCard> cards =
        list(
            "SELECT c.id FROM cards c JOIN placements p ON p.card_id=c.id "
                + "WHERE c.kind='MAIN' AND c.state='INSTALLED'",
            null);
    cards.sort(Comparator.comparing(MainCard::address));
    return List.copyOf(cards);
  }

  @Override
  public void insertDraft(MainCard draft) {
    requireDraft(draft);
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO cards(id,kind,state,revision,created_at) VALUES(?,'MAIN','DRAFT',?,?)")) {
      insert.setString(1, draft.id().toString());
      insert.setLong(2, draft.revision());
      insert.setString(3, draft.createdAt().toString());
      insert.executeUpdate();
      insertOriginalBody(draft);
      events.append("CardCreated", "MAIN_CARD", draft.id(), "{}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not create Main draft", exception);
    }
  }

  @Override
  public void updateDraft(MainCard draft, long expectedRevision) {
    requireDraft(draft);
    if (draft.revision() != expectedRevision + 1) {
      throw new IllegalArgumentException("Updated draft revision must be exactly one higher");
    }
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE cards SET revision=? WHERE id=? AND kind='MAIN' AND state='DRAFT' AND revision=?")) {
      update.setLong(1, draft.revision());
      update.setString(2, draft.id().toString());
      update.setLong(3, expectedRevision);
      if (update.executeUpdate() != 1)
        throw new PersistenceException("Draft revision changed or draft does not exist");
      try (PreparedStatement delete =
          connection.prepareStatement("DELETE FROM card_runs WHERE container_id=?")) {
        delete.setString(1, draft.id().toString());
        delete.executeUpdate();
      }
      insertRuns(draft.id(), draft.authoredBody());
      events.append(
          "DraftEdited", "MAIN_CARD", draft.id(), "{\"revision\":" + draft.revision() + "}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not update Main draft", exception);
    }
  }

  @Override
  public void install(MainCard installed, PlacementSnapshot placement, long expectedDraftRevision) {
    if (installed.state() != CardState.INSTALLED)
      throw new IllegalArgumentException("Card must be installed");
    Objects.requireNonNull(placement, "placement");
    if (!installed.id().equals(placement.mainCardId())
        || !installed.address().equals(placement.address())
        || !installed.installedAt().equals(placement.installedAt())
        || installed.revision() != expectedDraftRevision) {
      throw new IllegalArgumentException(
          "Placement and installed card must describe the same installation");
    }
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT revision FROM cards WHERE id=? AND kind='MAIN' AND state='DRAFT'")) {
      query.setString(1, installed.id().toString());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next() || row.getLong(1) != expectedDraftRevision)
          throw new PersistenceException("Draft changed or no longer exists");
      }
      events.append(
          "CardInstalled",
          "MAIN_CARD",
          installed.id(),
          "{\"addressLiteral\":\""
              + installed.address().literal()
              + "\",\"addressScheme\":\""
              + placement.schemeId()
              + "\",\"predecessorId\":"
              + nullableJson(placement.predecessorCardId())
              + ",\"successorId\":"
              + nullableJson(placement.successorCardId())
              + ",\"bodyRevision\":"
              + installed.revision()
              + "}");
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO placements(card_id,raw_address,canonical_address,scheme_id,scheme_version,sort_key,"
                  + "predecessor_card_id,successor_card_id,installed_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
        insert.setString(1, installed.id().toString());
        insert.setString(2, placement.address().literal());
        insert.setString(3, placement.address().canonical());
        insert.setString(4, placement.schemeId());
        insert.setInt(5, placement.schemeVersion());
        insert.setString(6, placement.address().sortKey());
        insert.setString(7, nullable(placement.predecessorCardId()));
        insert.setString(8, nullable(placement.successorCardId()));
        insert.setString(9, placement.installedAt().toString());
        try {
          insert.executeUpdate();
        } catch (SQLException exception) {
          if (isAddressCollision(exception))
            throw new AddressConflictException(placement.address().literal());
          throw exception;
        }
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not install Main card", exception);
    }
  }

  @Override
  public void appendAnnotation(UUID cardId, CardAnnotation annotation) {
    Objects.requireNonNull(cardId, "cardId");
    Objects.requireNonNull(annotation, "annotation");
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(ordinal)+1,0) FROM run_containers WHERE card_id=? AND container_kind='ANNOTATION'")) {
      query.setString(1, cardId.toString());
      int ordinal;
      try (ResultSet row = query.executeQuery()) {
        row.next();
        ordinal = row.getInt(1);
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO run_containers(id,card_id,container_kind,ordinal,created_at) VALUES(?,?,'ANNOTATION',?,?)")) {
        insert.setString(1, annotation.id().toString());
        insert.setString(2, cardId.toString());
        insert.setInt(3, ordinal);
        insert.setString(4, annotation.createdAt().toString());
        insert.executeUpdate();
      }
      insertRuns(annotation.id(), annotation.runs());
      events.append(
          "CardAnnotationAppended",
          "MAIN_CARD",
          cardId,
          "{\"annotationId\":\"" + annotation.id() + "\"}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not append Main card annotation", exception);
    }
  }

  private List<MainCard> list(String sql, String parameter) {
    List<UUID> ids = new ArrayList<>();
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      if (parameter != null) query.setString(1, parameter);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) ids.add(UUID.fromString(rows.getString(1)));
      }
      List<MainCard> result = new ArrayList<>(ids.size());
      for (UUID id : ids) findById(id).ifPresent(result::add);
      return result;
    } catch (SQLException | RuntimeException exception) {
      throw new PersistenceException("Could not list Main cards", exception);
    }
  }

  private MainCard readCard(ResultSet row) throws SQLException {
    UUID id = UUID.fromString(row.getString("id"));
    Instant createdAt = Instant.parse(row.getString("created_at"));
    CardState state = CardState.valueOf(row.getString("state"));
    long revision = row.getLong("revision");
    String installedAtLiteral = row.getString("installed_at");
    Instant installedAt = installedAtLiteral == null ? null : Instant.parse(installedAtLiteral);
    long bodyRevision = row.getLong("installed_body_revision");
    if (row.wasNull()) bodyRevision = -1;
    NumericAlphaAddress address = null;
    if (state == CardState.INSTALLED) {
      try (PreparedStatement query =
          connection.prepareStatement("SELECT raw_address FROM placements WHERE card_id=?")) {
        query.setString(1, id.toString());
        try (ResultSet placement = query.executeQuery()) {
          if (!placement.next()) throw new SQLException("Installed Main card has no placement");
          address = NumericAlphaAddress.parse(placement.getString(1));
        }
      }
    }
    List<BodyRun> original = loadRuns(id.toString());
    List<CardAnnotation> annotations = loadAnnotations(id);
    return MainCard.rehydrate(
        id, createdAt, state, revision, original, address, installedAt, bodyRevision, annotations);
  }

  private List<CardAnnotation> loadAnnotations(UUID cardId) throws SQLException {
    List<CardAnnotation> annotations = new ArrayList<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT id,created_at FROM run_containers WHERE card_id=? AND container_kind='ANNOTATION' ORDER BY ordinal")) {
      query.setString(1, cardId.toString());
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          UUID id = UUID.fromString(rows.getString(1));
          annotations.add(
              new CardAnnotation(id, Instant.parse(rows.getString(2)), loadRuns(id.toString())));
        }
      }
    }
    return List.copyOf(annotations);
  }

  private List<BodyRun> loadRuns(String containerId) throws SQLException {
    List<BodyRun> runs = new ArrayList<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT id,run_kind,authored_literal FROM card_runs WHERE container_id=? ORDER BY run_order")) {
      query.setString(1, containerId);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          UUID id = UUID.fromString(rows.getString(1));
          String literal = rows.getString(3);
          runs.add(
              switch (rows.getString(2)) {
                case "TEXT" -> new TextRun(id, literal);
                case "CARD_LINK" -> new CardLinkRun(id, literal);
                case "INDEX_KEYTERM_LINK" -> new KeytermLinkRun(id, literal);
                case "EXTERNAL_REFERENCE" -> new ExRefRun(id, literal);
                default -> throw new SQLException("Unknown stored card run type");
              });
        }
      }
    }
    return List.copyOf(runs);
  }

  private void insertOriginalBody(MainCard card) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO run_containers(id,card_id,container_kind,ordinal,created_at) VALUES(?,?,'ORIGINAL',0,?)")) {
      insert.setString(1, card.id().toString());
      insert.setString(2, card.id().toString());
      insert.setString(3, card.createdAt().toString());
      insert.executeUpdate();
    }
    insertRuns(card.id().toString(), card.authoredBody());
  }

  private void insertRuns(UUID containerId, List<BodyRun> runs) throws SQLException {
    insertRuns(containerId.toString(), runs);
  }

  private void insertRuns(String containerId, List<BodyRun> runs) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO card_runs(id,container_id,run_order,run_kind,authored_literal) VALUES(?,?,?,?,?)")) {
      for (int order = 0; order < runs.size(); order++) {
        BodyRun run = runs.get(order);
        insert.setString(1, run.id().toString());
        insert.setString(2, containerId);
        insert.setInt(3, order);
        insert.setString(4, runKind(run));
        insert.setString(5, run.literal());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  private static String runKind(BodyRun run) {
    if (run instanceof TextRun) return "TEXT";
    if (run instanceof CardLinkRun) return "CARD_LINK";
    if (run instanceof KeytermLinkRun) return "INDEX_KEYTERM_LINK";
    if (run instanceof ExRefRun) return "EXTERNAL_REFERENCE";
    throw new IllegalArgumentException("Unsupported card body run type");
  }

  private static boolean isAddressCollision(SQLException exception) {
    return exception.getMessage() != null
        && exception
            .getMessage()
            .contains("UNIQUE constraint failed: placements.canonical_address");
  }

  private static String nullable(UUID id) {
    return id == null ? null : id.toString();
  }

  private static String nullableJson(UUID id) {
    return id == null ? "null" : "\"" + id + "\"";
  }

  private static void requireDraft(MainCard card) {
    Objects.requireNonNull(card, "card");
    if (card.state() != CardState.DRAFT)
      throw new IllegalArgumentException("Repository accepts only draft cards");
  }
}
