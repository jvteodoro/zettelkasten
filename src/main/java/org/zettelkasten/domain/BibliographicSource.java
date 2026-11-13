package org.zettelkasten.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Imported or manually entered work metadata, separate from the user's BibCard. */
public record BibliographicSource(
    UUID id,
    String sourceType,
    String title,
    String creatorsText,
    String publicationYear,
    String containerTitle,
    String publisher,
    String edition,
    String volume,
    String issue,
    String pages,
    String doi,
    String isbn,
    String url,
    Map<String, String> rawFields,
    String rawRecord,
    Instant createdAt,
    Instant updatedAt) {
  public BibliographicSource {
    id = Objects.requireNonNull(id, "id");
    sourceType = Objects.requireNonNull(sourceType, "sourceType");
    title = Objects.requireNonNull(title, "title");
    creatorsText = Objects.requireNonNull(creatorsText, "creatorsText");
    rawFields = immutableFields(rawFields);
    rawRecord = Objects.requireNonNull(rawRecord, "rawRecord");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (sourceType.isBlank()) throw new IllegalArgumentException("sourceType cannot be blank");
  }

  private static Map<String, String> immutableFields(Map<String, String> fields) {
    Objects.requireNonNull(fields, "rawFields");
    LinkedHashMap<String, String> copy = new LinkedHashMap<>();
    fields.forEach(
        (name, value) ->
            copy.put(
                Objects.requireNonNull(name, "raw field name"),
                Objects.requireNonNull(value, "raw field value")));
    return Collections.unmodifiableMap(copy);
  }
}
