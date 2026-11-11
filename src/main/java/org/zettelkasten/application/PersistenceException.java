package org.zettelkasten.application;

/** Storage failure surfaced without exposing SQL, filesystem, or authored content. */
public final class PersistenceException extends RuntimeException {
  public PersistenceException(String message, Throwable cause) {
    super(message, cause);
  }

  public PersistenceException(String message) {
    super(message);
  }
}
