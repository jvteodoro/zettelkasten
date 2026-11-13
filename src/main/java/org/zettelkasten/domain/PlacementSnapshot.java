package org.zettelkasten.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence of an intentional filing position and its insertion neighbors. */
public record PlacementSnapshot(
    UUID mainCardId,
    NumericAlphaAddress address,
    UUID predecessorCardId,
    UUID successorCardId,
    Instant installedAt) {
  public PlacementSnapshot {
    mainCardId = Objects.requireNonNull(mainCardId, "mainCardId");
    address = Objects.requireNonNull(address, "address");
    installedAt = Objects.requireNonNull(installedAt, "installedAt");
    if (mainCardId.equals(predecessorCardId) || mainCardId.equals(successorCardId)) {
      throw new IllegalArgumentException("A card cannot be its own insertion neighbor");
    }
    if (predecessorCardId != null && predecessorCardId.equals(successorCardId)) {
      throw new IllegalArgumentException("Predecessor and successor must be distinct cards");
    }
  }

  public String schemeId() {
    return address.scheme().id();
  }

  public int schemeVersion() {
    return address.scheme().version();
  }
}
