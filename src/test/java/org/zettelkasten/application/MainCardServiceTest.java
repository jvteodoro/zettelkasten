package org.zettelkasten.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.zettelkasten.domain.CardState;
import org.zettelkasten.domain.MainCard;
import org.zettelkasten.domain.TextRun;
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
    MainCard second = service.createDraft(List.of(new TextRun(uuid(95), "still here")));
    service.installMainCard(first.id(), 0, "1");
    int eventCountBeforeRejectedInstalls = eventCount(database);

    assertThrows(AddressParseException.class, () -> service.installMainCard(second.id(), 0, "bad"));
    assertThrows(
        AddressConflictException.class, () -> service.installMainCard(second.id(), 0, "1"));
    assertTrue(service.getDrafts().stream().anyMatch(card -> card.id().equals(second.id())));
    assertEquals(eventCountBeforeRejectedInstalls, eventCount(database));
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

  private static UUID uuid(long value) {
    return new UUID(0, value);
  }
}
