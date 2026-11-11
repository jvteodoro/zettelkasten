package org.zettelkasten.domain;

/** Actionable validation failure for the restricted ANTINET_SLASH_V1 address dialect. */
public final class AddressParseException extends IllegalArgumentException {
  public AddressParseException(String message) {
    super(message);
  }
}
