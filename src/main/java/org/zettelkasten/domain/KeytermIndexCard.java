package org.zettelkasten.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A manually promoted, dedicated Index card for one keyterm. */
public record KeytermIndexCard(UUID id, String keyterm, Instant createdAt) implements IndexCard {
  public KeytermIndexCard {
    id = Objects.requireNonNull(id, "id");
    keyterm = Objects.requireNonNull(keyterm, "keyterm");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (keyterm.isBlank()) throw new IllegalArgumentException("keyterm cannot be blank");
  }
}
