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
import org.zettelkasten.application.port.IndexCardRepository;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.ListIndexCard;

/** JDBC adapter for the authored Index card identities. */
final class SqliteIndexCardRepository implements IndexCardRepository {
  private final Connection connection;

  SqliteIndexCardRepository(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  @Override
  public void insertListCard(ListIndexCard card) {
    Objects.requireNonNull(card, "card");
    try {
      insertBaseCard(card.id(), "INDEX_LIST", card.createdAt());
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO index_list_cards(card_id,divider,overflow_number) VALUES(?,?,?)")) {
        insert.setString(1, card.id().toString());
        insert.setString(2, String.valueOf(card.divider()));
        insert.setInt(3, card.overflowNumber());
        insert.executeUpdate();
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not create Index list card", exception);
    }
  }

  @Override
  public void insertKeytermCard(KeytermIndexCard card) {
    Objects.requireNonNull(card, "card");
    try {
      insertBaseCard(card.id(), "INDEX_KEYTERM", card.createdAt());
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO index_keyterm_cards(card_id,keyterm,created_at) VALUES(?,?,?)")) {
        insert.setString(1, card.id().toString());
        insert.setString(2, card.keyterm());
        insert.setString(3, card.createdAt().toString());
        insert.executeUpdate();
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not create Index keyterm card", exception);
    }
  }

  @Override
  public List<ListIndexCard> findListCards(char divider) {
    char canonicalDivider = Character.toUpperCase(divider);
    if (canonicalDivider < 'A' || canonicalDivider > 'Z')
      throw new IllegalArgumentException("divider must be A through Z");
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT i.card_id,i.divider,i.overflow_number,c.created_at FROM index_list_cards i "
                + "JOIN cards c ON c.id=i.card_id WHERE i.divider=? ORDER BY i.overflow_number")) {
      query.setString(1, String.valueOf(canonicalDivider));
      try (ResultSet rows = query.executeQuery()) {
        List<ListIndexCard> cards = new ArrayList<>();
        while (rows.next()) {
          cards.add(
              new ListIndexCard(
                  UUID.fromString(rows.getString(1)),
                  rows.getString(2).charAt(0),
                  rows.getInt(3),
                  Instant.parse(rows.getString(4))));
        }
        return List.copyOf(cards);
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not read Index list cards", exception);
    }
  }

  @Override
  public List<KeytermIndexCard> findKeytermCards() {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT card_id,keyterm,created_at FROM index_keyterm_cards "
                + "ORDER BY keyterm COLLATE NOCASE,card_id")) {
      try (ResultSet rows = query.executeQuery()) {
        List<KeytermIndexCard> cards = new ArrayList<>();
        while (rows.next()) {
          cards.add(
              new KeytermIndexCard(
                  UUID.fromString(rows.getString(1)),
                  rows.getString(2),
                  Instant.parse(rows.getString(3))));
        }
        return List.copyOf(cards);
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not read Index keyterm cards", exception);
    }
  }

  @Override
  public boolean contains(UUID cardId) {
    Objects.requireNonNull(cardId, "cardId");
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT 1 FROM cards WHERE id=? AND kind IN ('INDEX_LIST','INDEX_KEYTERM')")) {
      query.setString(1, cardId.toString());
      try (ResultSet row = query.executeQuery()) {
        return row.next();
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not check Index card", exception);
    }
  }

  private void insertBaseCard(UUID id, String kind, Instant createdAt) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO cards(id,kind,state,revision,created_at) VALUES(?,?,'INSTALLED',0,?)")) {
      insert.setString(1, id.toString());
      insert.setString(2, kind);
      insert.setString(3, createdAt.toString());
      insert.executeUpdate();
    }
  }
}
