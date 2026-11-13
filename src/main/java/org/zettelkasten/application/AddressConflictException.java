package org.zettelkasten.application;

/** A manually requested address is already assigned to another installed card. */
public final class AddressConflictException extends RuntimeException {
  public AddressConflictException(String address) {
    super("Address is already occupied: " + address);
  }
}
