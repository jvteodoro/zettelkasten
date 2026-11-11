package org.zettelkasten.infrastructure.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.zettelkasten.application.PersistenceException;
import org.zettelkasten.application.port.BibliographyRepository;
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibliographicSource;

/** JDBC adapter separating imported source metadata and authored bibliographic research. */
final class SqliteBibliographyRepository implements BibliographyRepository {
  private final Connection connection;

  SqliteBibliographyRepository(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
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
