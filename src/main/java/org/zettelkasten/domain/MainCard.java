package org.zettelkasten.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable aggregate value for a Desk draft or an installed Main card. */
public final class MainCard {
  private final UUID id;
  private final Instant createdAt;
  private final CardState state;
  private final long revision;
  private final List<BodyRun> authoredBody;
  private final String addressLiteral;
  private final Instant installedAt;
  private final long installedBodyRevision;
  private final List<CardAnnotation> annotations;

  private MainCard(
      UUID id,
      Instant createdAt,
      CardState state,
      long revision,
      List<BodyRun> authoredBody,
      String addressLiteral,
      Instant installedAt,
      long installedBodyRevision,
      List<CardAnnotation> annotations) {
    this.id = Objects.requireNonNull(id, "id");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.state = Objects.requireNonNull(state, "state");
    if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
    this.revision = revision;
    this.authoredBody = CardAnnotation.immutableRuns(authoredBody);
    this.addressLiteral = addressLiteral;
    this.installedAt = installedAt;
    this.installedBodyRevision = installedBodyRevision;
    this.annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
    if (state == CardState.DRAFT && (addressLiteral != null || installedAt != null)) {
      throw new IllegalArgumentException("Draft cards cannot have an address or install time");
    }
    if (state == CardState.INSTALLED
        && (addressLiteral == null || addressLiteral.isBlank() || installedAt == null)) {
      throw new IllegalArgumentException(
          "Installed cards require a chosen address and install time");
    }
  }

  public static MainCard createDraft(UUID id, Instant createdAt) {
    return createDraft(id, createdAt, List.of());
  }

  public static MainCard createDraft(UUID id, Instant createdAt, List<BodyRun> initialBody) {
    return new MainCard(id, createdAt, CardState.DRAFT, 0, initialBody, null, null, -1, List.of());
  }

  /** Replace a draft body with an optimistic revision check. */
  public MainCard editDraft(long expectedRevision, List<BodyRun> newBody) {
    requireDraft();
    if (expectedRevision != revision)
      throw new StaleDraftRevisionException(expectedRevision, revision);
    return new MainCard(id, createdAt, state, revision + 1, newBody, null, null, -1, annotations);
  }

  /** Freeze the current body and retain the manually chosen literal address. */
  public MainCard install(String chosenAddressLiteral, Instant time) {
    requireDraft();
    Objects.requireNonNull(chosenAddressLiteral, "chosenAddressLiteral");
    if (chosenAddressLiteral.isBlank())
      throw new IllegalArgumentException("Address cannot be blank");
    return new MainCard(
        id,
        createdAt,
        CardState.INSTALLED,
        revision,
        authoredBody,
        chosenAddressLiteral,
        Objects.requireNonNull(time, "time"),
        revision,
        annotations);
  }

  /** Append an immutable addendum without changing the installed body snapshot. */
  public MainCard appendAnnotation(CardAnnotation annotation) {
    requireInstalled();
    Objects.requireNonNull(annotation, "annotation");
    if (annotations.stream().anyMatch(existing -> existing.id().equals(annotation.id()))) {
      throw new IllegalArgumentException("Annotation ID already exists");
    }
    Set<UUID> usedRunIds = new HashSet<>();
    authoredBody.forEach(run -> usedRunIds.add(run.id()));
    annotations.forEach(existing -> existing.runs().forEach(run -> usedRunIds.add(run.id())));
    if (annotation.runs().stream().anyMatch(run -> usedRunIds.contains(run.id()))) {
      throw new IllegalArgumentException("Run IDs must be unique within a card aggregate");
    }
    List<CardAnnotation> appended = new ArrayList<>(annotations);
    appended.add(annotation);
    return new MainCard(
        id,
        createdAt,
        state,
        revision,
        authoredBody,
        addressLiteral,
        installedAt,
        installedBodyRevision,
        appended);
  }

  private void requireDraft() {
    if (state != CardState.DRAFT)
      throw new IllegalStateException("Installed maincard content is immutable");
  }

  private void requireInstalled() {
    if (state != CardState.INSTALLED)
      throw new IllegalStateException("Only installed cards accept annotations");
  }

  public UUID id() {
    return id;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public CardState state() {
    return state;
  }

  public long revision() {
    return revision;
  }

  public List<BodyRun> authoredBody() {
    return authoredBody;
  }

  public String addressLiteral() {
    return addressLiteral;
  }

  public Instant installedAt() {
    return installedAt;
  }

  public long installedBodyRevision() {
    return installedBodyRevision;
  }

  public List<CardAnnotation> annotations() {
    return annotations;
  }
}
