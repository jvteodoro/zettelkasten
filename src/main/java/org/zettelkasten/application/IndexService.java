package org.zettelkasten.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.zettelkasten.application.port.UnitOfWork;
import org.zettelkasten.domain.IndexEntry;
import org.zettelkasten.domain.IndexTarget;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.KeytermTarget;
import org.zettelkasten.domain.ListIndexCard;
import org.zettelkasten.domain.MainAddressTarget;
import org.zettelkasten.domain.NumericAlphaAddress;

/** Commands and deliberate read models for the authored Index box. */
public final class IndexService {
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public IndexService(UnitOfWork unitOfWork, Clock clock, Supplier<UUID> ids) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public static List<Character> dividers() {
    List<Character> dividers = new ArrayList<>(26);
    for (char divider = 'A'; divider <= 'Z'; divider++) dividers.add(divider);
    return List.copyOf(dividers);
  }

  public ListIndexCard createListCard(char divider, int overflowNumber) {
    ListIndexCard card = new ListIndexCard(ids.get(), divider, overflowNumber, clock.instant());
    unitOfWork.transact(
        tx -> {
          tx.indexCards().insertListCard(card);
          return null;
        });
    return card;
  }

  public KeytermIndexCard createKeytermCard(String keyterm) {
    KeytermIndexCard card = new KeytermIndexCard(ids.get(), keyterm, clock.instant());
    unitOfWork.transact(
        tx -> {
          tx.indexCards().insertKeytermCard(card);
          return null;
        });
    return card;
  }

  public IndexEntry addEntry(UUID ownerCardId, String authoredText, IndexTarget target) {
    Objects.requireNonNull(ownerCardId, "ownerCardId");
    Objects.requireNonNull(target, "target");
    return unitOfWork.transact(
        tx -> {
          if (!tx.indexCards().contains(ownerCardId))
            throw new IndexCardNotFoundException(ownerCardId);
          List<IndexEntry> existing = tx.indexCards().findEntries(ownerCardId);
          IndexTarget resolved = resolveTarget(tx, target);
          IndexEntry entry =
              new IndexEntry(
                  ids.get(),
                  ownerCardId,
                  existing.size(),
                  authoredText,
                  resolved,
                  clock.instant(),
                  null);
          return tx.indexCards().appendEntry(entry);
        });
  }

  public KeytermIndexCard promoteEntry(UUID listCardId, UUID entryId) {
    Objects.requireNonNull(listCardId, "listCardId");
    Objects.requireNonNull(entryId, "entryId");
    Instant promotedAt = clock.instant();
    return unitOfWork.transact(
        tx -> {
          if (!tx.indexCards().isListCard(listCardId))
            throw new IndexCardNotFoundException(listCardId);
          IndexEntry source =
              tx.indexCards().findEntries(listCardId).stream()
                  .filter(entry -> entry.id().equals(entryId))
                  .findFirst()
                  .orElseThrow(() -> new IndexEntryNotFoundException(entryId));
          if (source.promotedToKeytermCardId() != null)
            throw new IllegalStateException("Index entry has already been promoted");
          KeytermIndexCard card =
              new KeytermIndexCard(ids.get(), source.authoredText(), promotedAt);
          tx.indexCards().insertKeytermCard(card);
          IndexEntry copied =
              new IndexEntry(
                  ids.get(),
                  card.id(),
                  0,
                  source.authoredText(),
                  source.target(),
                  promotedAt,
                  null);
          IndexEntry persistedCopy = tx.indexCards().appendEntry(copied);
          tx.indexCards().recordPromotion(source, persistedCopy, card, promotedAt);
          return card;
        });
  }

  public List<ListIndexCard> getListCards(char divider) {
    return unitOfWork.transact(tx -> tx.indexCards().findListCards(divider));
  }

  public List<KeytermIndexCard> getKeytermCards() {
    return unitOfWork.transact(tx -> tx.indexCards().findKeytermCards());
  }

  public List<IndexEntry> getEntries(UUID ownerCardId) {
    return unitOfWork.transact(tx -> tx.indexCards().findEntries(ownerCardId));
  }

  private static IndexTarget resolveTarget(UnitOfWork.Transaction tx, IndexTarget target) {
    if (target instanceof MainAddressTarget mainAddress) {
      var address = NumericAlphaAddress.parse(mainAddress.literal());
      UUID installedId = tx.mainCards().findByAddress(address).map(card -> card.id()).orElse(null);
      if (mainAddress.targetMainCardId() != null
          && !mainAddress.targetMainCardId().equals(installedId)) {
        throw new IllegalArgumentException("Main target ID does not match its authored address");
      }
      return new MainAddressTarget(mainAddress.literal(), installedId);
    }
    if (target instanceof KeytermTarget keyterm && keyterm.targetKeytermCardId() != null) {
      boolean targetExists =
          tx.indexCards().findKeytermCards().stream()
              .anyMatch(card -> card.id().equals(keyterm.targetKeytermCardId()));
      if (!targetExists) throw new IndexCardNotFoundException(keyterm.targetKeytermCardId());
    }
    return target;
  }
}
