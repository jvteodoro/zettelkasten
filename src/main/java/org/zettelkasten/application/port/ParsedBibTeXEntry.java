package org.zettelkasten.application.port;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Parser-neutral BibTeX record. {@code rawFile} retains the complete original UTF-8 input. */
public record ParsedBibTeXEntry(
    String citationKey, String entryType, Map<String, String> fields, String rawFile) {
  public ParsedBibTeXEntry {
    citationKey = required(citationKey, "citationKey");
    entryType = required(entryType, "entryType");
    Objects.requireNonNull(fields, "fields");
    fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    rawFile = Objects.requireNonNull(rawFile, "rawFile");
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
    return value;
  }
}
