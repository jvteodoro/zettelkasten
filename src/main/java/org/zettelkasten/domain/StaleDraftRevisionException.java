package org.zettelkasten.domain;

/** Raised when an edit was based on a draft revision that has already changed. */
public final class StaleDraftRevisionException extends RuntimeException {
  public StaleDraftRevisionException(long expected, long actual) {
    super("Stale draft revision: expected " + expected + " but current revision is " + actual);
  }
}
