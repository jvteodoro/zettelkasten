package org.zettelkasten.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Dry-run or applied per-file result; conflicts and errors are visible without merging. */
public record BibTeXImportResult(UUID batchId, Status status, List<EntryResult> entries) {
  public enum Status {
    DRY_RUN,
    APPLIED,
    REJECTED
  }

  public enum Outcome {
    NEW,
    CHANGED,
    CONFLICT,
    UNCHANGED,
    ERROR
  }

  public record EntryResult(String citationKey, Outcome outcome, UUID sourceId, String diagnostic) {
    public EntryResult {
      citationKey = Objects.requireNonNull(citationKey, "citationKey");
      outcome = Objects.requireNonNull(outcome, "outcome");
      diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }
  }

  public BibTeXImportResult {
    batchId = Objects.requireNonNull(batchId, "batchId");
    status = Objects.requireNonNull(status, "status");
    entries = List.copyOf(entries);
  }
}
