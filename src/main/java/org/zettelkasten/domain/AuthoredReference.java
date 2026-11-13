package org.zettelkasten.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Typed authored edge metadata. It records references but offers no navigation operation. */
public record AuthoredReference(
    UUID id,
    UUID sourceCardId,
    UUID sourceRunId,
    ReferenceKind kind,
    String authoredLiteral,
    String targetLiteral,
    UUID targetCardId,
    UUID targetSourceId,
    UUID targetIndexId,
    ResolutionStatus status,
    Instant createdAt) {
  public AuthoredReference {
    id = Objects.requireNonNull(id, "id");
    sourceCardId = Objects.requireNonNull(sourceCardId, "sourceCardId");
    sourceRunId = Objects.requireNonNull(sourceRunId, "sourceRunId");
    kind = Objects.requireNonNull(kind, "kind");
    authoredLiteral = Objects.requireNonNull(authoredLiteral, "authoredLiteral");
    targetLiteral = Objects.requireNonNull(targetLiteral, "targetLiteral");
    status = Objects.requireNonNull(status, "status");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    validateTypedTarget(kind, targetCardId, targetSourceId, targetIndexId, status);
  }

  public static AuthoredReference card(
      UUID id, UUID sourceCardId, UUID sourceRunId, AddressReference target, Instant createdAt) {
    Objects.requireNonNull(target, "target");
    return new AuthoredReference(
        id,
        sourceCardId,
        sourceRunId,
        ReferenceKind.valueOf(target.kind().name()),
        target.literal(),
        target.literal(),
        null,
        null,
        null,
        ResolutionStatus.PENDING,
        createdAt);
  }

  public static AuthoredReference keyterm(
      UUID id, UUID sourceCardId, UUID sourceRunId, String literal, Instant createdAt) {
    return pending(id, sourceCardId, sourceRunId, ReferenceKind.INDEX_KEYTERM, literal, createdAt);
  }

  public static AuthoredReference external(
      UUID id, UUID sourceCardId, UUID sourceRunId, String literal, Instant createdAt) {
    return pending(
        id, sourceCardId, sourceRunId, ReferenceKind.BIBLIOGRAPHIC_SOURCE, literal, createdAt);
  }

  public AuthoredReference resolveCard(UUID stableTargetId) {
    requireKind(ReferenceKind.STEM, ReferenceKind.BRANCH, ReferenceKind.FULL);
    return copy(
        Objects.requireNonNull(stableTargetId, "stableTargetId"),
        null,
        null,
        ResolutionStatus.RESOLVED);
  }

  public AuthoredReference resolveIndex(UUID stableTargetId) {
    requireKind(ReferenceKind.INDEX_KEYTERM);
    return copy(
        null,
        null,
        Objects.requireNonNull(stableTargetId, "stableTargetId"),
        ResolutionStatus.RESOLVED);
  }

  public AuthoredReference resolveSource(UUID stableTargetId) {
    requireKind(ReferenceKind.BIBLIOGRAPHIC_SOURCE);
    return copy(
        null,
        Objects.requireNonNull(stableTargetId, "stableTargetId"),
        null,
        ResolutionStatus.RESOLVED);
  }

  public AuthoredReference markAmbiguous() {
    return copy(null, null, null, ResolutionStatus.AMBIGUOUS);
  }

  public AuthoredReference markBroken() {
    return copy(targetCardId, targetSourceId, targetIndexId, ResolutionStatus.BROKEN);
  }

  private AuthoredReference copy(
      UUID cardId, UUID sourceId, UUID indexId, ResolutionStatus nextStatus) {
    return new AuthoredReference(
        id,
        sourceCardId,
        sourceRunId,
        kind,
        authoredLiteral,
        targetLiteral,
        cardId,
        sourceId,
        indexId,
        nextStatus,
        createdAt);
  }

  private void requireKind(ReferenceKind... permitted) {
    for (ReferenceKind candidate : permitted) if (kind == candidate) return;
    throw new IllegalStateException("Resolution target type does not match " + kind);
  }

  private static AuthoredReference pending(
      UUID id,
      UUID sourceCardId,
      UUID sourceRunId,
      ReferenceKind kind,
      String literal,
      Instant createdAt) {
    return new AuthoredReference(
        id,
        sourceCardId,
        sourceRunId,
        kind,
        Objects.requireNonNull(literal, "literal"),
        literal,
        null,
        null,
        null,
        ResolutionStatus.PENDING,
        createdAt);
  }

  private static void validateTypedTarget(
      ReferenceKind kind,
      UUID targetCardId,
      UUID targetSourceId,
      UUID targetIndexId,
      ResolutionStatus status) {
    boolean correctTargetPresent =
        switch (kind) {
          case STEM, BRANCH, FULL -> {
            if (targetSourceId != null || targetIndexId != null)
              throw new IllegalArgumentException(
                  "Card reference cannot point to a source or Index ID");
            yield targetCardId != null;
          }
          case INDEX_KEYTERM -> {
            if (targetCardId != null || targetSourceId != null)
              throw new IllegalArgumentException("Index reference requires an Index target");
            yield targetIndexId != null;
          }
          case BIBLIOGRAPHIC_SOURCE -> {
            if (targetCardId != null || targetIndexId != null)
              throw new IllegalArgumentException(
                  "External reference requires a bibliographic source target");
            yield targetSourceId != null;
          }
        };
    if (status == ResolutionStatus.RESOLVED && !correctTargetPresent)
      throw new IllegalArgumentException("Resolved references require a stable typed target ID");
    if ((status == ResolutionStatus.PENDING || status == ResolutionStatus.AMBIGUOUS)
        && correctTargetPresent)
      throw new IllegalArgumentException(status + " references cannot expose a chosen target ID");
  }
}
