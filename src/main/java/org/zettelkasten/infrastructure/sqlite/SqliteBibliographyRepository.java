package org.zettelkasten.infrastructure.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.zettelkasten.application.PersistenceException;
import org.zettelkasten.application.port.BibliographyRepository;
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibNote;
import org.zettelkasten.domain.BibliographicSource;
import org.zettelkasten.domain.SourceExternalId;

/** JDBC adapter separating imported source metadata and authored bibliographic research. */
final class SqliteBibliographyRepository implements BibliographyRepository {
  private final Connection connection;
  private final SqliteEventJournal events;

  SqliteBibliographyRepository(Connection connection, SqliteEventJournal events) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.events = Objects.requireNonNull(events, "events");
  }

  @Override
  public void insertSource(BibliographicSource source) {
    Objects.requireNonNull(source, "source");
    String sql =
        "INSERT INTO bibliographic_sources(id,source_type,title,creators_text,publication_year,container_title,"
            + "publisher,edition,volume,issue,pages,doi,isbn,url,raw_record,metadata_json,created_at,updated_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, source.id().toString());
      insert.setString(2, source.sourceType());
      insert.setString(3, source.title());
      insert.setString(4, source.creatorsText());
      insert.setString(5, source.publicationYear());
      insert.setString(6, source.containerTitle());
      insert.setString(7, source.publisher());
      insert.setString(8, source.edition());
      insert.setString(9, source.volume());
      insert.setString(10, source.issue());
      insert.setString(11, source.pages());
      insert.setString(12, source.doi());
      insert.setString(13, source.isbn());
      insert.setString(14, source.url());
      insert.setString(15, source.rawRecord());
      insert.setString(16, "{}");
      insert.setString(17, source.createdAt().toString());
      insert.setString(18, source.updatedAt().toString());
      insert.executeUpdate();
      insertRawFields(source);
      events.append("BibliographicSourceCreated", "BIBLIOGRAPHIC_SOURCE", source.id(), "{}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not create bibliographic source", exception);
    }
  }

  @Override
  public Optional<BibliographicSource> findSource(UUID sourceId) {
    Objects.requireNonNull(sourceId, "sourceId");
    String sql =
        "SELECT id,source_type,title,creators_text,publication_year,container_title,publisher,edition,volume,issue,"
            + "pages,doi,isbn,url,raw_record,created_at,updated_at FROM bibliographic_sources WHERE id=?";
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, sourceId.toString());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new BibliographicSource(
                UUID.fromString(row.getString(1)),
                row.getString(2),
                row.getString(3),
                row.getString(4),
                row.getString(5),
                row.getString(6),
                row.getString(7),
                row.getString(8),
                row.getString(9),
                row.getString(10),
                row.getString(11),
                row.getString(12),
                row.getString(13),
                row.getString(14),
                loadRawFields(sourceId),
                row.getString(15),
                Instant.parse(row.getString(16)),
                Instant.parse(row.getString(17))));
      }
    } catch (SQLException | RuntimeException exception) {
      throw new PersistenceException("Could not read bibliographic source", exception);
    }
  }

  @Override
  public void insertBibCard(BibCard card) {
    Objects.requireNonNull(card, "card");
    try (PreparedStatement insertCard =
        connection.prepareStatement(
            "INSERT INTO cards(id,kind,state,revision,created_at) VALUES(?,'BIB','INSTALLED',0,?)")) {
      insertCard.setString(1, card.id().toString());
      insertCard.setString(2, card.createdAt().toString());
      insertCard.executeUpdate();
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO bibcards(card_id,source_id,reading_goal,source_overview) VALUES(?,?,?,?)")) {
        insert.setString(1, card.id().toString());
        insert.setString(2, card.sourceId().toString());
        insert.setString(3, card.readingGoal());
        insert.setString(4, card.sourceOverview());
        insert.executeUpdate();
      }
      events.append(
          "BibCardCreated", "BIB_CARD", card.id(), "{\"sourceId\":\"" + card.sourceId() + "\"}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not create BibCard", exception);
    }
  }

  @Override
  public Optional<BibCard> findBibCard(UUID cardId) {
    Objects.requireNonNull(cardId, "cardId");
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT b.card_id,b.source_id,c.created_at,b.reading_goal,b.source_overview "
                + "FROM bibcards b JOIN cards c ON c.id=b.card_id WHERE b.card_id=?")) {
      query.setString(1, cardId.toString());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new BibCard(
                UUID.fromString(row.getString(1)),
                UUID.fromString(row.getString(2)),
                Instant.parse(row.getString(3)),
                row.getString(4),
                row.getString(5)));
      }
    } catch (SQLException | RuntimeException exception) {
      throw new PersistenceException("Could not read BibCard", exception);
    }
  }

  @Override
  public List<BibliographicSource> findSources() {
    List<UUID> ids = new ArrayList<>();
    try (PreparedStatement query =
            connection.prepareStatement(
                "SELECT id FROM bibliographic_sources ORDER BY title COLLATE NOCASE,id");
        ResultSet rows = query.executeQuery()) {
      while (rows.next()) ids.add(UUID.fromString(rows.getString(1)));
      return ids.stream().map(this::findSource).flatMap(Optional::stream).toList();
    } catch (SQLException exception) {
      throw new PersistenceException("Could not list bibliographic sources", exception);
    }
  }

  @Override
  public List<BibliographicSource> findSourcesByDoi(String doi) {
    Objects.requireNonNull(doi, "doi");
    List<UUID> ids = new ArrayList<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT id FROM bibliographic_sources WHERE lower(doi)=lower(?) ORDER BY id")) {
      query.setString(1, doi);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) ids.add(UUID.fromString(rows.getString(1)));
      }
      return ids.stream().map(this::findSource).flatMap(Optional::stream).toList();
    } catch (SQLException exception) {
      throw new PersistenceException("Could not find bibliographic DOI", exception);
    }
  }

  @Override
  public Optional<UUID> findSourceIdByExternalId(String provider, String scope, String externalId) {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT source_id FROM source_external_ids WHERE provider=? AND scope=? AND external_id=?")) {
      query.setString(1, provider);
      query.setString(2, scope);
      query.setString(3, externalId);
      try (ResultSet row = query.executeQuery()) {
        return row.next() ? Optional.of(UUID.fromString(row.getString(1))) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not resolve source external identity", exception);
    }
  }

  @Override
  public void insertSourceExternalId(SourceExternalId externalId) {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO source_external_ids(id,source_id,provider,scope,external_id) VALUES(?,?,?,?,?)")) {
      insert.setString(1, externalId.id().toString());
      insert.setString(2, externalId.sourceId().toString());
      insert.setString(3, externalId.provider());
      insert.setString(4, externalId.scope());
      insert.setString(5, externalId.externalId());
      insert.executeUpdate();
      events.append("BibliographicAliasAdded", "BIBLIOGRAPHIC_SOURCE", externalId.sourceId(), "{}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not attach source external identity", exception);
    }
  }

  @Override
  public void insertBibNote(BibNote note) {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO bibnotes(id,bibcard_id,ordinal,locator_type,locator_literal,authored_text,created_at,derived_main_card_id) "
                + "VALUES(?,?,?,?,?,?,?,?)")) {
      insert.setString(1, note.id().toString());
      insert.setString(2, note.bibCardId().toString());
      insert.setInt(3, note.ordinal());
      insert.setString(4, note.locatorType().name());
      insert.setString(5, note.locatorLiteral());
      insert.setString(6, note.authoredText());
      insert.setString(7, note.createdAt().toString());
      insert.setString(
          8, note.derivedMainCardId() == null ? null : note.derivedMainCardId().toString());
      insert.executeUpdate();
      events.append(
          "BibNoteAdded", "BIB_CARD", note.bibCardId(), "{\"ordinal\":" + note.ordinal() + "}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not append BibNote", exception);
    }
  }

  @Override
  public List<BibNote> findBibNotes(UUID bibCardId) {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT id,bibcard_id,ordinal,locator_type,locator_literal,authored_text,created_at,derived_main_card_id "
                + "FROM bibnotes WHERE bibcard_id=? ORDER BY ordinal")) {
      query.setString(1, bibCardId.toString());
      try (ResultSet rows = query.executeQuery()) {
        List<BibNote> notes = new ArrayList<>();
        while (rows.next()) {
          String derived = rows.getString(8);
          notes.add(
              new BibNote(
                  UUID.fromString(rows.getString(1)),
                  UUID.fromString(rows.getString(2)),
                  rows.getInt(3),
                  BibNote.LocatorType.valueOf(rows.getString(4)),
                  rows.getString(5),
                  rows.getString(6),
                  Instant.parse(rows.getString(7)),
                  derived == null ? null : UUID.fromString(derived)));
        }
        return List.copyOf(notes);
      }
    } catch (SQLException | RuntimeException exception) {
      throw new PersistenceException("Could not read BibNotes", exception);
    }
  }

  @Override
  public void insertImportBatch(
      UUID id,
      String namespace,
      String sourceName,
      String contentSha256,
      String status,
      Instant importedAt) {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO import_batches(id,provider,namespace,source_name,content_sha256,status,imported_at) "
                + "VALUES(?,'BIBTEX_FILE',?,?,?,?,?)")) {
      insert.setString(1, id.toString());
      insert.setString(2, namespace);
      insert.setString(3, sourceName);
      insert.setString(4, contentSha256);
      insert.setString(5, status);
      insert.setString(6, importedAt.toString());
      insert.executeUpdate();
      events.append(
          "BibTeXImport" + ("APPLIED".equals(status) ? "Applied" : "Rejected"),
          "IMPORT_BATCH",
          id,
          "{\"status\":\"" + status + "\",\"sha256\":\"" + contentSha256 + "\"}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not persist BibTeX import batch", exception);
    }
  }

  @Override
  public void insertImportEntry(
      UUID id,
      UUID batchId,
      UUID sourceId,
      String externalKey,
      String rawRecord,
      String outcome,
      String diagnostic) {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO import_entries(id,batch_id,source_id,external_key,raw_record,outcome,diagnostic) "
                + "VALUES(?,?,?,?,?,?,?)")) {
      insert.setString(1, id.toString());
      insert.setString(2, batchId.toString());
      insert.setString(3, sourceId == null ? null : sourceId.toString());
      insert.setString(4, externalKey);
      insert.setString(5, rawRecord);
      insert.setString(6, outcome);
      insert.setString(7, diagnostic);
      insert.executeUpdate();
    } catch (SQLException exception) {
      throw new PersistenceException("Could not persist BibTeX import entry", exception);
    }
  }

  private void insertRawFields(BibliographicSource source) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO bibliographic_source_fields(source_id,field_order,field_name,field_value) VALUES(?,?,?,?)")) {
      int order = 0;
      for (Map.Entry<String, String> field : source.rawFields().entrySet()) {
        insert.setString(1, source.id().toString());
        insert.setInt(2, order++);
        insert.setString(3, field.getKey());
        insert.setString(4, field.getValue());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  private Map<String, String> loadRawFields(UUID sourceId) throws SQLException {
    Map<String, String> fields = new LinkedHashMap<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT field_name,field_value FROM bibliographic_source_fields WHERE source_id=? ORDER BY field_order")) {
      query.setString(1, sourceId.toString());
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) fields.put(rows.getString(1), rows.getString(2));
      }
    }
    return fields;
  }
}
