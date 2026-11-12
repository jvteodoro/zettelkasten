package org.zettelkasten.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zettelkasten.domain.AddressParseException;
import org.zettelkasten.domain.CardAnnotation;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.CardState;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.KeytermLinkRun;
import org.zettelkasten.domain.MainCard;
import org.zettelkasten.domain.TextRun;
import org.zettelkasten.infrastructure.bibtex.JBibTeXFileParser;
import org.zettelkasten.infrastructure.sqlite.SqliteDatabase;

class MainCardServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @TempDir java.nio.file.Path temp;

  @Test
  void draftPersistsWithoutAddressAndReopensForEditing() {
    var database = database("desk.db");
    var service = service(database);
    MainCard created = service.createDraft(List.of(new TextRun(uuid(90), "Ideia — 漢字")));
    assertEquals(CardState.DRAFT, created.state());
    assertEquals(null, created.addressLiteral());

    var reopened = service(database);
    MainCard restored = reopened.getDrafts().getFirst();
    assertEquals(created.id(), restored.id());
    MainCard edited =
        reopened.editDraft(created.id(), 0, List.of(new TextRun(uuid(91), "Ideia revisada")));
    assertEquals(1, edited.revision());
    assertThrows(
        org.zettelkasten.domain.StaleDraftRevisionException.class,
        () -> reopened.editDraft(created.id(), 0, List.of(new TextRun(uuid(98), "stale"))));
    assertEquals(
        "Ideia revisada",
        service(database).getDrafts().getFirst().authoredBody().getFirst().literal());
  }

  @Test
  void discardingDraftTombstonesItAndWritesOneTransactionalEvent() {
    var database = database("discard.db");
    var service = service(database);
    MainCard draft = service.createDraft(List.of(new TextRun(uuid(189), "retained content")));
    service.discardDraft(draft.id(), draft.revision());
    assertTrue(service.getDrafts().isEmpty());
    MainCard restored =
        database.unitOfWork().transact(tx -> tx.mainCards().findById(draft.id()).orElseThrow());
    assertEquals(CardState.DISCARDED, restored.state());
    assertEquals("retained content", restored.authoredBody().getFirst().literal());
    assertThrows(IllegalStateException.class, restored::discard);
    assertThrows(IllegalStateException.class, () -> restored.editDraft(0, List.of()));
    long discardEvents =
        database
            .unitOfWork()
            .transact(
                tx ->
                    tx.events().findAfter(0, 100).stream()
                        .filter(
                            event ->
                                event.aggregateId().equals(draft.id())
                                    && event.eventType().equals("DraftDiscarded"))
                        .count());
    assertEquals(1L, discardEvents);
    assertThrows(RuntimeException.class, () -> service.discardDraft(draft.id(), draft.revision()));
  }

  @Test
  void manualInstallationKeepsIdentityAndReturnsIndependentPhysicalNeighbors() {
    var database = database("neighbors.db");
    var service = service(database);
    MainCard left = service.createDraft(List.of(new TextRun(uuid(92), "left")));
    MainCard right = service.createDraft(List.of(new TextRun(uuid(93), "right")));
    MainCard middle = service.createDraft(List.of(new TextRun(uuid(94), "middle")));
    service.installMainCard(left.id(), 0, "3118/2");
    service.installMainCard(right.id(), 0, "3118/3");
    var insertionNeighbors = service.getInsertionNeighbors("3118/2a");
    assertEquals(left.id(), insertionNeighbors.predecessor().orElseThrow().id());
    assertEquals(right.id(), insertionNeighbors.successor().orElseThrow().id());
    MainCard installed = service.installMainCard(middle.id(), 0, "3118/2a");

    assertEquals(middle.id(), installed.id());
    assertEquals(
        "3118/2a", service.getMainByExactAddress("3118/2A").orElseThrow().addressLiteral());
    var neighbors = service.getOrderedNeighbors("3118/2a");
    assertEquals(left.id(), neighbors.predecessor().orElseThrow().id());
    assertEquals(right.id(), neighbors.successor().orElseThrow().id());
    assertEquals(
        List.of("3118/2", "3118/2a", "3118/3"),
        service.getInstalledInAddressOrder().stream().map(MainCard::addressLiteral).toList());
    assertEquals(0, service.getMainByExactAddress("3118/2").orElseThrow().revision());
    String installationPayload =
        database
            .unitOfWork()
            .transact(
                tx ->
                    tx.events().findAfter(0, 100).stream()
                        .filter(event -> event.aggregateId().equals(middle.id()))
                        .filter(event -> event.eventType().equals("CardInstalled"))
                        .findFirst()
                        .orElseThrow()
                        .payloadJson());
    assertTrue(installationPayload.contains("\"predecessorId\":\"" + left.id() + "\""));
    assertTrue(installationPayload.contains("\"successorId\":\"" + right.id() + "\""));
  }

  @Test
  void invalidOrDuplicateAddressLeavesDraftAndEventHistoryUntouched() {
    var database = database("invalid-install.db");
    var service = service(database);
    MainCard first = service.createDraft(List.of());
    MainCard second = service.createDraft(List.of(new CardLinkRun(uuid(95), "11")));
    service.installMainCard(first.id(), 0, "1");
    int eventCountBeforeRejectedInstalls = eventCount(database);

    assertThrows(AddressParseException.class, () -> service.installMainCard(second.id(), 0, "bad"));
    assertThrows(
        AddressConflictException.class, () -> service.installMainCard(second.id(), 0, "1"));
    assertTrue(service.getDrafts().stream().anyMatch(card -> card.id().equals(second.id())));
    assertEquals(eventCountBeforeRejectedInstalls, eventCount(database));
    var retainedReference =
        database
            .unitOfWork()
            .transact(tx -> tx.references().findBySourceCard(second.id()).getFirst());
    assertEquals("PENDING", retainedReference.status().name());
    assertEquals("11", retainedReference.authoredLiteral());
    assertFalse(service.getMainByExactAddress("11").isPresent());
  }

  @Test
  void installedOriginalIsImmutableAndAnnotationIsAppendOnly() {
    var database = database("annotation.db");
    var service = service(database);
    MainCard draft = service.createDraft(List.of(new TextRun(uuid(96), "Original")));
    MainCard installed = service.installMainCard(draft.id(), 0, "9");
    CardAnnotation annotation =
        service.appendAnnotation(installed.id(), List.of(new TextRun(uuid(97), "Correction")));

    MainCard restored = service.getMainByExactAddress("9").orElseThrow();
    assertEquals("Original", restored.authoredBody().getFirst().literal());
    assertEquals(annotation.id(), restored.annotations().getFirst().id());
    assertEquals("Correction", restored.annotations().getFirst().runs().getFirst().literal());
    assertThrows(IllegalStateException.class, () -> installed.editDraft(0, List.of()));
  }

  @Test
  void referencesArePersistedResolvedAtFilingAndResolvedWhenTargetIsInstalledLater() {
    var database = database("reference-lifecycle.db");
    var service = service(database);
    MainCard stemTarget = service.createDraft(List.of(new TextRun(uuid(501), "Stem target")));
    MainCard branchTarget = service.createDraft(List.of(new TextRun(uuid(502), "Branch target")));
    service.installMainCard(stemTarget.id(), 0, "4214/2/1");
    service.installMainCard(branchTarget.id(), 0, "4214/1/1a");
    MainCard source =
        service.createDraft(
            List.of(
                new CardLinkRun(uuid(503), "/1"),
                new CardLinkRun(uuid(504), "/1"),
                new CardLinkRun(uuid(505), "~/1/1a"),
                new CardLinkRun(uuid(506), "9000/1"),
                new KeytermLinkRun(uuid(507), "Argument"),
                new ExRefRun(uuid(508), "b.paper, p. 27"),
                new ExRefRun(uuid(516), "r.Zetteldan.1")));

    var pending =
        database.unitOfWork().transact(tx -> tx.references().findBySourceCard(source.id()));
    assertEquals(7, pending.size());
    assertTrue(pending.stream().allMatch(reference -> reference.status().name().equals("PENDING")));
    assertEquals(
        2, pending.stream().filter(reference -> reference.targetLiteral().equals("/1")).count());

    service.installMainCard(source.id(), 0, "4214/2");
    var afterFiling =
        database.unitOfWork().transact(tx -> tx.references().findBySourceCard(source.id()));
    assertEquals(stemTarget.id(), afterFiling.get(0).targetCardId());
    assertEquals(stemTarget.id(), afterFiling.get(1).targetCardId());
    assertEquals(branchTarget.id(), afterFiling.get(2).targetCardId());
    assertEquals("9000/1", afterFiling.get(3).targetLiteral());
    assertEquals("PENDING", afterFiling.get(3).status().name());
    assertEquals("PENDING", afterFiling.get(4).status().name());
    assertEquals("PENDING", afterFiling.get(5).status().name());

    MainCard laterTarget = service.createDraft(List.of(new TextRun(uuid(509), "Later target")));
    service.installMainCard(laterTarget.id(), 0, "9000/1");
    var afterLateTarget =
        database.unitOfWork().transact(tx -> tx.references().findBySourceCard(source.id()));
    assertEquals(laterTarget.id(), afterLateTarget.get(3).targetCardId());
    assertEquals("RESOLVED", afterLateTarget.get(3).status().name());

    var indexService =
        new IndexService(database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), () -> uuid(520));
    KeytermIndexCard term = indexService.createKeytermCard("Argument");
    var afterIndexTarget =
        database.unitOfWork().transact(tx -> tx.references().findBySourceCard(source.id()));
    assertEquals(term.id(), afterIndexTarget.get(4).targetIndexId());
    assertEquals("RESOLVED", afterIndexTarget.get(4).status().name());

    var bibliography =
        new BibliographyService(
            database.unitOfWork(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            ids(530),
            new JBibTeXFileParser());
    var imported = bibliography.applyImport("library", "sources.bib", "@book{paper,title={Paper}}");
    var afterSourceTarget =
        database.unitOfWork().transact(tx -> tx.references().findBySourceCard(source.id()));
    assertEquals(
        imported.entries().getFirst().sourceId(), afterSourceTarget.get(5).targetSourceId());
    assertEquals("b.paper, p. 27", afterSourceTarget.get(5).targetLiteral());
    assertEquals("RESOLVED", afterSourceTarget.get(5).status().name());
    assertEquals("PENDING", afterSourceTarget.get(6).status().name());
    assertEquals("r.Zetteldan.1", afterSourceTarget.get(6).targetLiteral());
    long resolutionEvents =
        database
            .unitOfWork()
            .transact(
                tx ->
                    tx.events().findAfter(0, 100).stream()
                        .filter(event -> event.aggregateId().equals(source.id()))
                        .filter(event -> event.eventType().equals("ReferenceResolutionChanged"))
                        .count());
    assertEquals(6L, resolutionEvents);
  }

  @Test
  void typedReferencesBecomeAmbiguousWhenLiteralMatchesMultipleManualTargets() {
    var database = database("ambiguous-references.db");
    var index = new IndexService(database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), ids(540));
    index.createKeytermCard("Argument");
    index.createKeytermCard("Argument");
    var bibliography =
        new BibliographyService(
            database.unitOfWork(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            ids(550),
            new JBibTeXFileParser());
    bibliography.applyImport("library-a", "a.bib", "@book{paper,title={One}}");
    bibliography.applyImport("library-b", "b.bib", "@book{paper,title={Two}}");

    MainCard source =
        service(database)
            .createDraft(
                List.of(
                    new KeytermLinkRun(uuid(551), "Argument"),
                    new ExRefRun(uuid(552), "b.paper, p. 27")));
    var references =
        database.unitOfWork().transact(tx -> tx.references().findBySourceCard(source.id()));

    assertEquals("AMBIGUOUS", references.get(0).status().name());
    assertEquals("AMBIGUOUS", references.get(1).status().name());
    assertNull(references.get(0).targetIndexId());
    assertNull(references.get(1).targetSourceId());
  }

  @Test
  void referencesInsideAppendOnlyAnnotationsArePersistedWithTheirRunIdentity() {
    var database = database("annotation-reference.db");
    var service = service(database);
    MainCard source = service.createDraft(List.of(new TextRun(uuid(510), "Source")));
    service.installMainCard(source.id(), 0, "17");
    var annotation =
        service.appendAnnotation(source.id(), List.of(new CardLinkRun(uuid(511), "33/1")));
    var reference =
        database
            .unitOfWork()
            .transact(tx -> tx.references().findBySourceCard(source.id()).getFirst());
    assertEquals(uuid(511), reference.sourceRunId());
    assertEquals("33/1", reference.authoredLiteral());
    assertEquals("33/1", reference.targetLiteral());
    assertTrue(annotation.runs().getFirst() instanceof CardLinkRun);
  }

  @Test
  void editingADraftReplacesItsReferenceProjectionWithoutLeavingOrphans() {
    var database = database("edited-reference.db");
    var service = service(database);
    MainCard draft = service.createDraft(List.of(new CardLinkRun(uuid(512), "/1")));
    assertEquals(
        uuid(512),
        database
            .unitOfWork()
            .transact(tx -> tx.references().findBySourceCard(draft.id()).getFirst().sourceRunId()));

    service.editDraft(draft.id(), 0, List.of(new CardLinkRun(uuid(513), "35/2")));
    var references =
        database.unitOfWork().transact(tx -> tx.references().findBySourceCard(draft.id()));
    assertEquals(1, references.size());
    assertEquals(uuid(513), references.getFirst().sourceRunId());
    assertEquals("35/2", references.getFirst().authoredLiteral());
    assertTrue(
        new org.zettelkasten.infrastructure.sqlite.ArchiveOperations(database).doctor().healthy());
  }

  private SqliteDatabase database(String file) {
    AtomicLong generated = new AtomicLong(100);
    return SqliteDatabase.create(
        temp.resolve(file),
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> uuid(generated.incrementAndGet()));
  }

  private MainCardService service(SqliteDatabase database) {
    AtomicLong generated = new AtomicLong(200);
    return new MainCardService(
        database.unitOfWork(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> uuid(generated.incrementAndGet()));
  }

  private static int eventCount(SqliteDatabase database) {
    return database.unitOfWork().transact(tx -> tx.events().findAfter(0, 100).size());
  }

  private static java.util.function.Supplier<UUID> ids(long start) {
    AtomicLong generated = new AtomicLong(start);
    return () -> uuid(generated.incrementAndGet());
  }

  private static UUID uuid(long value) {
    return new UUID(0, value);
  }
}
