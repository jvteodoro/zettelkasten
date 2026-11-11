package org.zettelkasten.domain;

import java.util.UUID;

/** Plain authored text; text content is never interpreted as a reference. */
public record TextRun(UUID id, String literal) implements BodyRun {
  public TextRun {
    id = RunValidation.id(id);
    literal = RunValidation.literal(literal);
  }
}
