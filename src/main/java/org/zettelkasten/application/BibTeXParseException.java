package org.zettelkasten.application;

/** A malformed BibTeX file rejected before any sources are committed. */
public final class BibTeXParseException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public BibTeXParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
