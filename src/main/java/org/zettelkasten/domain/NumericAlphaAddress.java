package org.zettelkasten.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Parsed address whose canonical value is separate from the original user literal. */
public final class NumericAlphaAddress implements Comparable<NumericAlphaAddress> {
  public static final int MAX_LENGTH = 255;
  public static final int MAX_DEPTH = 32;
  private static final Pattern ROOT = Pattern.compile("[0-9]+");
  private static final Pattern SEGMENT = Pattern.compile("([0-9]+)([A-Za-z]+)?([0-9]*)");

  private final String literal;
  private final String canonical;
  private final List<Segment> segments;

  private NumericAlphaAddress(String literal, String canonical, List<Segment> segments) {
    this.literal = literal;
    this.canonical = canonical;
    this.segments = List.copyOf(segments);
  }

  public static NumericAlphaAddress parse(String literal) {
    Objects.requireNonNull(literal, "literal");
    if (literal.isEmpty()) throw new AddressParseException("Address cannot be empty");
    if (literal.length() > MAX_LENGTH) {
      throw new AddressParseException("Address exceeds the " + MAX_LENGTH + " character limit");
    }
    if (!literal.equals(literal.strip())) {
      throw new AddressParseException("Leading or trailing whitespace is not allowed");
    }
    String[] parts = literal.split("/", -1);
    if (parts.length > MAX_DEPTH) {
      throw new AddressParseException("Address exceeds the " + MAX_DEPTH + " segment limit");
    }
    if (!ROOT.matcher(parts[0]).matches()) {
      throw new AddressParseException("The first address segment must contain decimal digits only");
    }
    List<Segment> parsed = new ArrayList<>(parts.length);
    List<String> canonicalParts = new ArrayList<>(parts.length);
    for (int index = 0; index < parts.length; index++) {
      String part = parts[index];
      if (part.isEmpty()) throw new AddressParseException("Address segments cannot be empty");
      var matcher = SEGMENT.matcher(part);
      if (!matcher.matches()) {
        throw new AddressParseException("Invalid address segment: '" + part + "'");
      }
      String number = matcher.group(1);
      String letters = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.ROOT);
      String suffix = matcher.group(3);
      if (hasLeadingZero(number) || hasLeadingZero(suffix)) {
        throw new AddressParseException(
            "Multi-digit numeric components cannot have leading zeroes");
      }
      parsed.add(
          new Segment(
              new BigInteger(number), letters, suffix.isEmpty() ? null : new BigInteger(suffix)));
      canonicalParts.add(number + letters + suffix);
    }
    return new NumericAlphaAddress(literal, String.join("/", canonicalParts), parsed);
  }

  private static boolean hasLeadingZero(String digits) {
    return digits.length() > 1 && digits.charAt(0) == '0';
  }

  public String literal() {
    return literal;
  }

  public String canonical() {
    return canonical;
  }

  public AddressScheme scheme() {
    return AddressScheme.ANTINET_SLASH_V1;
  }

  @Override
  public int compareTo(NumericAlphaAddress other) {
    Objects.requireNonNull(other, "other");
    int limit = Math.min(segments.size(), other.segments.size());
    for (int i = 0; i < limit; i++) {
      int result = segments.get(i).compareTo(other.segments.get(i));
      if (result != 0) return result;
    }
    return Integer.compare(segments.size(), other.segments.size());
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof NumericAlphaAddress address && canonical.equals(address.canonical);
  }

  @Override
  public int hashCode() {
    return canonical.hashCode();
  }

  @Override
  public String toString() {
    return literal;
  }

  private record Segment(BigInteger number, String letters, BigInteger suffix)
      implements Comparable<Segment> {
    private Segment {
      Objects.requireNonNull(number, "number");
      Objects.requireNonNull(letters, "letters");
    }

    @Override
    public int compareTo(Segment other) {
      int result = number.compareTo(other.number);
      if (result != 0) return result;
      result = letters.compareTo(other.letters);
      if (result != 0) return result;
      if (suffix == null) return other.suffix == null ? 0 : -1;
      return other.suffix == null ? 1 : suffix.compareTo(other.suffix);
    }
  }
}
