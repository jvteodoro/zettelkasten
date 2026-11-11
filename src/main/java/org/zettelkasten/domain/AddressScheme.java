package org.zettelkasten.domain;

/** Versioned persisted address schemes; changing a grammar creates a new enum entry. */
public enum AddressScheme {
  ANTINET_SLASH_V1("ANTINET_SLASH_V1", 1);

  private final String id;
  private final int version;

  AddressScheme(String id, int version) {
    this.id = id;
    this.version = version;
  }

  public String id() {
    return id;
  }

  public int version() {
    return version;
  }
}
