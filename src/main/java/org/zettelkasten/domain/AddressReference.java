package org.zettelkasten.domain;

import java.util.Objects;
import java.util.Optional;

/** Authored full, stem or branch address literal, with resolution separate from parsing. */
public final class AddressReference {
  public enum Kind {
    STEM,
    BRANCH,
    FULL
  }

  private final String literal;
  private final Kind kind;
  private final String relativePath;
  private final NumericAlphaAddress fullAddress;

  private AddressReference(
      String literal, Kind kind, String relativePath, NumericAlphaAddress fullAddress) {
    this.literal = literal;
    this.kind = kind;
    this.relativePath = relativePath;
    this.fullAddress = fullAddress;
  }

  public static AddressReference parse(String literal) {
    Objects.requireNonNull(literal, "literal");
    if (literal.startsWith("~/")) {
      String path = literal.substring(2);
      NumericAlphaAddress.parse(path);
      return new AddressReference(literal, Kind.BRANCH, path, null);
    }
    if (literal.startsWith("/")) {
      String path = literal.substring(1);
      NumericAlphaAddress.parse(path);
      return new AddressReference(literal, Kind.STEM, path, null);
    }
    return new AddressReference(literal, Kind.FULL, null, NumericAlphaAddress.parse(literal));
  }

  public String literal() {
    return literal;
  }

  public Kind kind() {
    return kind;
  }

  /**
   * Full addresses resolve immediately; relative references remain pending without a source
   * placement.
   */
  public Optional<NumericAlphaAddress> resolve(NumericAlphaAddress sourceAddress) {
    if (kind == Kind.FULL) return Optional.of(fullAddress);
    if (sourceAddress == null) return Optional.empty();
    String root = sourceAddress.canonical().split("/", -1)[0];
    String target =
        kind == Kind.STEM
            ? sourceAddress.canonical() + "/" + relativePath
            : root + "/" + relativePath;
    return Optional.of(NumericAlphaAddress.parse(target));
  }
}
