package org.zettelkasten.domain;

import java.util.Objects;

/** An authored See Also line kept as literal text without inferred target relationships. */
public record SeeAlsoTarget(String literal) implements IndexTarget {
  public SeeAlsoTarget {
    literal = Objects.requireNonNull(literal, "literal");
    if (literal.isBlank()) throw new IllegalArgumentException("See Also literal cannot be blank");
  }
}
