package org.zettelkasten.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A sequentially filled Index card under one A–Z divider. */
public record ListIndexCard(UUID id, char divider, int overflowNumber, Instant createdAt)
    implements IndexCard {
  public ListIndexCard {
    id = Objects.requireNonNull(id, "id");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    divider = Character.toUpperCase(divider);
    if (divider < 'A' || divider > 'Z')
      throw new IllegalArgumentException("divider must be A through Z");
    if (overflowNumber < 1) throw new IllegalArgumentException("overflowNumber must be positive");
  }
}
