package org.zettelkasten.application;

import java.util.UUID;

/** Raised when an application command requires a Main card that is not in the archive. */
public final class MainCardNotFoundException extends RuntimeException {
  public MainCardNotFoundException(UUID id) {
    super("Main card does not exist: " + id);
  }

  public MainCardNotFoundException(String address) {
    super("No Main card is filed at address: " + address);
  }
}
