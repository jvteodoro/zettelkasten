package org.zettelkasten.domain;

import java.util.UUID;

/** A deliberately authored Index keyterm pointer. */
public record KeytermLinkRun(UUID id, String literal) implements BodyRun {
  public KeytermLinkRun {
    id = RunValidation.id(id);
    literal = RunValidation.literal(literal);
  }
}
