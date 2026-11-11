package org.zettelkasten.domain;

import java.util.UUID;

/** A deliberately inserted card reference, including the literal as originally typed. */
public record CardLinkRun(UUID id, AddressReference target) implements BodyRun {
  public CardLinkRun {
    id = RunValidation.id(id);
    target = java.util.Objects.requireNonNull(target, "target");
  }

  public CardLinkRun(UUID id, String literal) {
    this(id, AddressReference.parse(literal));
  }

  @Override
  public String literal() {
    return target.literal();
  }
}
