package org.zettelkasten.application;

import java.util.UUID;

/** Raised when an explicit Index entry operation refers to a missing authored entry. */
public final class IndexEntryNotFoundException extends RuntimeException {
  public IndexEntryNotFoundException(UUID id) {
    super("Index entry does not exist: " + id);
  }
}
