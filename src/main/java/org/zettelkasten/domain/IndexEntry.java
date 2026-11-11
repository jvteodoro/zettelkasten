package org.zettelkasten.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only authored line on a list or dedicated Index card. */
public record IndexEntry(
    UUID id,
    UUID ownerCardId,
    int entryOrder,
    String authoredText,
    IndexTarget target,
    Instant createdAt,
    UUID promotedToKeytermCardId) {
  public IndexEntry {
    id = Objects.requireNonNull(id, "id");
    ownerCardId = Objects.requireNonNull(ownerCardId, "ownerCardId");
    if (entryOrder < 0) throw new IllegalArgumentException("entryOrder must be nonnegative");
    authoredText = Objects.requireNonNull(authoredText, "authoredText");
    if (authoredText.isBlank()) throw new IllegalArgumentException("authoredText cannot be blank");
    target = Objects.requireNonNull(target, "target");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public IndexEntry withOrder(int order) {
    return new IndexEntry(
        id, ownerCardId, order, authoredText, target, createdAt, promotedToKeytermCardId);
  }

  public IndexEntry promotedTo(UUID keytermCardId) {
    if (promotedToKeytermCardId != null)
      throw new IllegalStateException("Index entry has already been promoted");
    return new IndexEntry(
        id, ownerCardId, entryOrder, authoredText, target, createdAt, keytermCardId);
  }
}
