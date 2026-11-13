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
import org.zettelkasten.domain.IndexEntry;
import org.zettelkasten.domain.IndexTarget;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.KeytermTarget;
import org.zettelkasten.domain.ListIndexCard;
import org.zettelkasten.domain.MainAddressTarget;
import org.zettelkasten.domain.SeeAlsoTarget;

/** JDBC adapter for the authored Index card identities. */
final class SqliteIndexCardRepository implements IndexCardRepository {
  private final Connection connection;
  private final SqliteEventJournal events;

  SqliteIndexCardRepository(Connection connection, SqliteEventJournal events) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.events = Objects.requireNonNull(events, "events");
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
      events.append(
          "IndexCardCreated",
          "INDEX_CARD",
          card.id(),
          "{\"kind\":\"LIST\",\"divider\":\"" + card.divider() + "\"}");
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
      events.append("IndexCardCreated", "INDEX_CARD", card.id(), "{\"kind\":\"KEYTERM\"}");
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
  public IndexEntry appendEntry(IndexEntry entry) {
    Objects.requireNonNull(entry, "entry");
    try {
      String kind = ownerKind(entry.ownerCardId());
      String table = entryTable(kind);
      int order;
      try (PreparedStatement query =
          connection.prepareStatement(
              "SELECT COALESCE(MAX(entry_order)+1,0) FROM "
                  + table
                  + " WHERE "
                  + ownerColumn(kind)
                  + "=?")) {
        query.setString(1, entry.ownerCardId().toString());
        try (ResultSet row = query.executeQuery()) {
          row.next();
          order = row.getInt(1);
        }
      }
      String sql =
          "INSERT INTO "
              + table
              + "(id,"
              + ownerColumn(kind)
              + ",entry_order,authored_text,target_kind,target_literal,target_main_card_id,target_index_card_id,created_at) "
              + "VALUES(?,?,?,?,?,?,?,?,?)";
      try (PreparedStatement insert = connection.prepareStatement(sql)) {
        bindEntry(insert, entry, order);
        insert.executeUpdate();
      }
      IndexEntry saved = entry.withOrder(order);
      events.append(
          "IndexEntryAdded",
          "INDEX_CARD",
          entry.ownerCardId(),
          "{\"entryId\":\""
              + entry.id()
              + "\",\"targetKind\":\""
              + targetKind(entry.target())
              + "\"}");
      return saved;
    } catch (SQLException exception) {
      throw new PersistenceException("Could not append Index entry", exception);
    }
  }

  @Override
  public List<IndexEntry> findEntries(UUID ownerCardId) {
    Objects.requireNonNull(ownerCardId, "ownerCardId");
    try {
      String kind = ownerKind(ownerCardId);
      String table = entryTable(kind);
      String owner = ownerColumn(kind);
      String promotionJoin =
          "INDEX_LIST".equals(kind)
              ? " LEFT JOIN index_promotions p ON p.source_entry_id=e.id "
              : "";
      String promotionSelect = "INDEX_LIST".equals(kind) ? ",p.keyterm_card_id" : ",NULL";
      String sql =
          "SELECT e.id,e."
              + owner
              + ",e.entry_order,e.authored_text,e.target_kind,e.target_literal,e.target_main_card_id,"
              + "e.target_index_card_id,e.created_at"
              + promotionSelect
              + " FROM "
              + table
              + " e"
              + promotionJoin
              + " WHERE e."
              + owner
              + "=? ORDER BY e.entry_order";
      try (PreparedStatement query = connection.prepareStatement(sql)) {
        query.setString(1, ownerCardId.toString());
        try (ResultSet rows = query.executeQuery()) {
          List<IndexEntry> result = new ArrayList<>();
          while (rows.next()) result.add(readEntry(rows));
          return List.copyOf(result);
        }
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not read authored Index entries", exception);
    }
  }

  @Override
  public boolean isListCard(UUID cardId) {
    Objects.requireNonNull(cardId, "cardId");
    try (PreparedStatement query =
        connection.prepareStatement("SELECT 1 FROM index_list_cards WHERE card_id=?")) {
      query.setString(1, cardId.toString());
      try (ResultSet row = query.executeQuery()) {
        return row.next();
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not inspect Index list card", exception);
    }
  }

  @Override
  public void recordPromotion(
      IndexEntry sourceEntry,
      IndexEntry promotedEntry,
      KeytermIndexCard keytermCard,
      Instant promotedAt) {
    Objects.requireNonNull(sourceEntry, "sourceEntry");
    Objects.requireNonNull(promotedEntry, "promotedEntry");
    Objects.requireNonNull(keytermCard, "keytermCard");
    Objects.requireNonNull(promotedAt, "promotedAt");
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO index_promotions(source_entry_id,keyterm_card_id,promoted_entry_id,promoted_at) VALUES(?,?,?,?)")) {
      insert.setString(1, sourceEntry.id().toString());
      insert.setString(2, keytermCard.id().toString());
      insert.setString(3, promotedEntry.id().toString());
      insert.setString(4, promotedAt.toString());
      insert.executeUpdate();
      events.append(
          "IndexEntryRedirected",
          "INDEX_CARD",
          sourceEntry.ownerCardId(),
          "{\"sourceEntryId\":\""
              + sourceEntry.id()
              + "\",\"keytermCardId\":\""
              + keytermCard.id()
              + "\",\"promotedEntryId\":\""
              + promotedEntry.id()
              + "\"}");
    } catch (SQLException exception) {
      throw new PersistenceException("Could not record Index promotion history", exception);
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

  private String ownerKind(UUID cardId) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT kind FROM cards WHERE id=? AND kind IN ('INDEX_LIST','INDEX_KEYTERM')")) {
      query.setString(1, cardId.toString());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) throw new IllegalArgumentException("Index card does not exist: " + cardId);
        return row.getString(1);
      }
    }
  }

  private static String entryTable(String ownerKind) {
    return switch (ownerKind) {
      case "INDEX_LIST" -> "index_entries";
      case "INDEX_KEYTERM" -> "keyterm_entries";
      default -> throw new IllegalArgumentException("Not an Index card kind: " + ownerKind);
    };
  }

  private static String ownerColumn(String ownerKind) {
    return "INDEX_LIST".equals(ownerKind) ? "index_card_id" : "keyterm_card_id";
  }

  private void bindEntry(PreparedStatement insert, IndexEntry entry, int order)
      throws SQLException {
    insert.setString(1, entry.id().toString());
    insert.setString(2, entry.ownerCardId().toString());
    insert.setInt(3, order);
    insert.setString(4, entry.authoredText());
    insert.setString(5, targetKind(entry.target()));
    insert.setString(6, entry.target().literal());
    insert.setString(7, targetMainId(entry.target()));
    insert.setString(8, targetIndexId(entry.target()));
    insert.setString(9, entry.createdAt().toString());
  }

  private IndexEntry readEntry(ResultSet row) throws SQLException {
    String targetKind = row.getString(5);
    UUID targetMainId = uuid(row.getString(7));
    UUID targetIndexId = uuid(row.getString(8));
    IndexTarget target =
        switch (targetKind) {
          case "MAIN_ADDRESS" -> new MainAddressTarget(row.getString(6), targetMainId);
          case "INDEX_KEYTERM" -> new KeytermTarget(row.getString(6), targetIndexId);
          case "SEE_ALSO" -> new SeeAlsoTarget(row.getString(6));
          default -> throw new SQLException("Unknown Index target kind: " + targetKind);
        };
    return new IndexEntry(
        UUID.fromString(row.getString(1)),
        UUID.fromString(row.getString(2)),
        row.getInt(3),
        row.getString(4),
        target,
        Instant.parse(row.getString(9)),
        uuid(row.getString(10)));
  }

  private static String targetKind(IndexTarget target) {
    if (target instanceof MainAddressTarget) return "MAIN_ADDRESS";
    if (target instanceof KeytermTarget) return "INDEX_KEYTERM";
    if (target instanceof SeeAlsoTarget) return "SEE_ALSO";
    throw new IllegalArgumentException("Unsupported Index target");
  }

  private static String targetMainId(IndexTarget target) {
    return target instanceof MainAddressTarget address
        ? nullable(address.targetMainCardId())
        : null;
  }

  private static String targetIndexId(IndexTarget target) {
    return target instanceof KeytermTarget keyterm ? nullable(keyterm.targetKeytermCardId()) : null;
  }

  private static String nullable(UUID id) {
    return id == null ? null : id.toString();
  }

  private static UUID uuid(String id) {
    return id == null ? null : UUID.fromString(id);
  }
}
