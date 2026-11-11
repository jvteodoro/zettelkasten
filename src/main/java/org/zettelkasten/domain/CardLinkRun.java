package org.zettelkasten.domain;

import java.util.UUID;

/** A deliberately inserted card reference, including the literal as originally typed. */
public record CardLinkRun(UUID id, String literal) implements BodyRun {
  public CardLinkRun {
    id = RunValidation.id(id);
    literal = RunValidation.literal(literal);
  }
}
