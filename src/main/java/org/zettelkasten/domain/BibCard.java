package org.zettelkasten.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** User-owned research card associated with, but distinct from, a bibliographic source. */
public record BibCard(
    UUID id, UUID sourceId, Instant createdAt, String readingGoal, String sourceOverview) {
  public BibCard {
    id = Objects.requireNonNull(id, "id");
    sourceId = Objects.requireNonNull(sourceId, "sourceId");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    readingGoal = Objects.requireNonNull(readingGoal, "readingGoal");
    sourceOverview = Objects.requireNonNull(sourceOverview, "sourceOverview");
  }
}
