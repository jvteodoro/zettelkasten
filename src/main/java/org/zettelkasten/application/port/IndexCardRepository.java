package org.zettelkasten.application.port;

import java.util.List;
import java.util.UUID;
import org.zettelkasten.domain.IndexEntry;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.ListIndexCard;

/** Persistence port for manually authored Index card identities. */
public interface IndexCardRepository {
  void insertListCard(ListIndexCard card);

  void insertKeytermCard(KeytermIndexCard card);

  List<ListIndexCard> findListCards(char divider);

  List<KeytermIndexCard> findKeytermCards();

  IndexEntry appendEntry(IndexEntry entry);

  List<IndexEntry> findEntries(UUID ownerCardId);

  boolean isListCard(UUID cardId);

  void recordPromotion(
      IndexEntry sourceEntry,
      IndexEntry promotedEntry,
      KeytermIndexCard keytermCard,
      java.time.Instant promotedAt);

  boolean contains(UUID cardId);
}
