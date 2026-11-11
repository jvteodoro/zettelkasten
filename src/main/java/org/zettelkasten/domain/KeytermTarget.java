package org.zettelkasten.domain;

import java.util.Objects;
import java.util.UUID;

/** An authored keyterm pointer or redirect, optionally bound to a dedicated Index card. */
public record KeytermTarget(String literal, UUID targetKeytermCardId) implements IndexTarget {
  public KeytermTarget {
    literal = Objects.requireNonNull(literal, "literal");
    if (literal.isBlank()) throw new IllegalArgumentException("keyterm literal cannot be blank");
  }

  public KeytermTarget(String literal) {
    this(literal, null);
  }
}
