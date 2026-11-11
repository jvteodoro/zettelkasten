package org.zettelkasten.domain;

import java.util.UUID;

/** A deliberately authored external bibliographic reference. */
public record ExRefRun(UUID id, String literal) implements BodyRun {
  public ExRefRun {
    id = RunValidation.id(id);
    literal = RunValidation.literal(literal);
  }
}
