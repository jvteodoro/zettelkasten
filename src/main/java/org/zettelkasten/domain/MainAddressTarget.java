package org.zettelkasten.domain;

import java.util.Objects;
import java.util.UUID;

/** An authored pointer to a literal Main address, optionally resolved to its stable card UUID. */
public record MainAddressTarget(String literal, UUID targetMainCardId) implements IndexTarget {
  public MainAddressTarget {
    literal = Objects.requireNonNull(literal, "literal");
    NumericAlphaAddress.parse(literal);
  }

  public MainAddressTarget(String literal) {
    this(literal, null);
  }
}
