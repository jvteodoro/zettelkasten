package org.zettelkasten.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable addendum attached to an installed maincard. */
public record CardAnnotation(UUID id, Instant createdAt, List<BodyRun> runs) {
  public CardAnnotation {
    id = Objects.requireNonNull(id, "id");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    runs = immutableRuns(runs);
  }

  static List<BodyRun> immutableRuns(List<BodyRun> source) {
    List<BodyRun> copy = List.copyOf(Objects.requireNonNull(source, "runs"));
    Set<UUID> ids = new HashSet<>();
    for (BodyRun run : copy) {
      if (!ids.add(run.id()))
        throw new IllegalArgumentException("Run IDs must be unique within a body");
    }
    return copy;
  }
}
