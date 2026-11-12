package org.zettelkasten.domain;

import java.util.Objects;
import java.util.UUID;

/** Immutable provider identity scoped to a file namespace or future Zotero library. */
public record SourceExternalId(
    UUID id, UUID sourceId, String provider, String scope, String externalId) {
  public SourceExternalId {
    id = Objects.requireNonNull(id, "id");
    sourceId = Objects.requireNonNull(sourceId, "sourceId");
    provider = required(provider, "provider");
    scope = required(scope, "scope");
    externalId = required(externalId, "externalId");
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
    return value;
  }
}
