package org.zettelkasten.application;

import java.util.UUID;

/** Raised when an authored Index operation refers to a missing Index card. */
public final class IndexCardNotFoundException extends RuntimeException {
  public IndexCardNotFoundException(UUID id) {
    super("Index card does not exist: " + id);
  }
}
