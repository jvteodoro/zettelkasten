package org.zettelkasten.domain;

import java.util.Objects;
import java.util.UUID;

final class RunValidation {
  private RunValidation() {}

  static UUID id(UUID id) {
    return Objects.requireNonNull(id, "id");
  }

  static String literal(String literal) {
    return Objects.requireNonNull(literal, "literal");
  }
}
