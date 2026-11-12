package org.zettelkasten.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** User-authored observation located in a source using its literal page/time/section notation. */
public record BibNote(
    UUID id,
    UUID bibCardId,
    int ordinal,
    LocatorType locatorType,
    String locatorLiteral,
    String authoredText,
    Instant createdAt,
    UUID derivedMainCardId) {
  public enum LocatorType {
    PAGE,
    TIME,
    SECTION,
    OTHER
  }

  public BibNote {
    id = Objects.requireNonNull(id, "id");
    bibCardId = Objects.requireNonNull(bibCardId, "bibCardId");
    if (ordinal < 0) throw new IllegalArgumentException("ordinal must be nonnegative");
    locatorType = Objects.requireNonNull(locatorType, "locatorType");
    locatorLiteral = Objects.requireNonNull(locatorLiteral, "locatorLiteral");
    authoredText = Objects.requireNonNull(authoredText, "authoredText");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (locatorLiteral.isBlank())
      throw new IllegalArgumentException("locatorLiteral cannot be blank");
    if (authoredText.isBlank()) throw new IllegalArgumentException("authoredText cannot be blank");
  }
}
