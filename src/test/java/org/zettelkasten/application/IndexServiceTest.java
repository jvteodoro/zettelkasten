package org.zettelkasten.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zettelkasten.domain.DomainEvent;
import org.zettelkasten.domain.IndexEntry;
import org.zettelkasten.domain.KeytermTarget;
import org.zettelkasten.domain.MainAddressTarget;
import org.zettelkasten.domain.SeeAlsoTarget;
import org.zettelkasten.domain.TextRun;
import org.zettelkasten.infrastructure.sqlite.SqliteDatabase;

class IndexServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @TempDir java.nio.file.Path temp;

  @Test
  void dividersOverflowCardsAndManualEntriesKeepAuthoredOrder() {
    var service = service(database("ordered-index.db"));
    assertEquals(26, IndexService.dividers().size());
    var first = service.createListCard('a', 1);
    var overflow = service.createListCard('A', 2);
    var argument = service.addEntry(first.id(), "Argument", new MainAddressTarget("3118/99"));
    var aggressivita =
        service.addEntry(first.id(), "Aggressivität", new SeeAlsoTarget("See also: Argument"));

    assertEquals(List.of(first, overflow), service.getListCards('a'));
    assertEquals(List.of(argument, aggressivita), service.getEntries(first.id()));
    assertEquals(0, argument.entryOrder());
    assertEquals(1, aggressivita.entryOrder());
    assertNull(((MainAddressTarget) argument.target()).targetMainCardId());
    assertEquals("3118/99", argument.target().literal());
  }

  @Test
  void absentTargetsRemainLiteralAndDoNotCreateMainOrIndexCards() {
    var database = database("unresolved-index.db");
    var service = service(database);
    var list = service.createListCard('T', 1);
    var mainService =
        new MainCardService(database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), sequence(800));
    mainService.createDraft(List.of(new TextRun(uuid(70), "Argument appears in prose")));
    IndexEntry pointer = service.addEntry(list.id(), "Argument", new MainAddressTarget("700/1"));
    IndexEntry redirect =
        service.addEntry(list.id(), "Also see", new KeytermTarget("Unavailable term"));

    assertNull(((MainAddressTarget) pointer.target()).targetMainCardId());
    assertNull(((KeytermTarget) redirect.target()).targetKeytermCardId());
    assertEquals(1, mainService.getDrafts().size());
    assertEquals(List.of(list), service.getListCards('T'));
  }

  @Test
  void dedicatedKeytermsAreAlphabetizedAndSupportManualTargetsAndSeeAlso() {
    var service = service(database("keyterms.db"));
    var u = service.createKeytermCard("Überblick");
    var a = service.createKeytermCard("Argument");
    var target = service.addEntry(a.id(), "pointer", new MainAddressTarget("9/1"));
    var seeAlso = service.addEntry(u.id(), "See also", new SeeAlsoTarget("Argument"));

    assertEquals(List.of(a, u), service.getKeytermCards());
    assertEquals(List.of(target), service.getEntries(a.id()));
    assertEquals(List.of(seeAlso), service.getEntries(u.id()));
  }

  @Test
  void promotionCopiesPointerAndRetainsTheOriginalEntryAndEventProvenance() {
    var database = database("promotion.db");
    var service = service(database);
    var list = service.createListCard('A', 1);
    var source = service.addEntry(list.id(), "Argument", new MainAddressTarget("3118/2a"));
    var promoted = service.promoteEntry(list.id(), source.id());

    var historicalEntry = service.getEntries(list.id()).getFirst();
    var dedicatedEntry = service.getEntries(promoted.id()).getFirst();
    assertEquals(source.id(), historicalEntry.id());
    assertEquals(promoted.id(), historicalEntry.promotedToKeytermCardId());
    assertEquals("Argument", promoted.keyterm());
    assertEquals("3118/2a", dedicatedEntry.target().literal());
    assertNull(((MainAddressTarget) dedicatedEntry.target()).targetMainCardId());

    List<String> eventTypes =
        database
            .unitOfWork()
            .transact(
                tx -> tx.events().findAfter(0, 100).stream().map(DomainEvent::eventType).toList());
    assertEquals(
        List.of(
            "IndexCardCreated",
            "IndexEntryAdded",
            "IndexCardCreated",
            "IndexEntryAdded",
            "IndexEntryRedirected"),
        eventTypes);
    assertThrows(IllegalStateException.class, () -> service.promoteEntry(list.id(), source.id()));
    assertEquals(1, service.getListCards('A').size());
  }

  @Test
  void duplicateOverflowNumberAndInvalidDividerAreRejectedWithoutEvents() {
    var database = database("dividers.db");
    var service = service(database);
    var first = service.createListCard('A', 1);
    int eventCount = database.unitOfWork().transact(tx -> tx.events().findAfter(0, 20).size());
    assertThrows(PersistenceException.class, () -> service.createListCard('A', 1));
    assertThrows(IllegalArgumentException.class, () -> service.createListCard('/', 2));
    assertEquals(List.of(first), service.getListCards('A'));
    Integer actualEventCount =
        database.unitOfWork().transact(tx -> tx.events().findAfter(0, 20).size());
    assertEquals(Integer.valueOf(eventCount), actualEventCount);
    assertFalse(service.getListCards('B').contains(first));
  }

  @Test
  void authoredEntriesAndPromotionSurviveDatabaseReopen() {
    var database = database("index-reopen.db");
    var service = service(database);
    var list = service.createListCard('A', 1);
    var source = service.addEntry(list.id(), "Argument", new MainAddressTarget("3118/99", null));
    var promoted = service.promoteEntry(list.id(), source.id());

    var reopened = SqliteDatabase.open(temp.resolve("index-reopen.db"));
    var reopenedService = service(reopened);
    var retained = reopenedService.getEntries(list.id()).getFirst();
    assertEquals(source.id(), retained.id());
    assertEquals(source.authoredText(), retained.authoredText());
    assertEquals(source.target(), retained.target());
    assertEquals(promoted.id(), retained.promotedToKeytermCardId());
    assertEquals(promoted, reopenedService.getKeytermCards().getFirst());
    assertEquals(source.target(), reopenedService.getEntries(promoted.id()).getFirst().target());
    assertEquals(
        List.of(
            "IndexCardCreated",
            "IndexEntryAdded",
            "IndexCardCreated",
            "IndexEntryAdded",
            "IndexEntryRedirected"),
        reopened
            .unitOfWork()
            .transact(
                tx -> tx.events().findAfter(0, 20).stream().map(DomainEvent::eventType).toList()));
  }

  private SqliteDatabase database(String name) {
    return SqliteDatabase.create(temp.resolve(name), Clock.fixed(NOW, ZoneOffset.UTC), sequence(1));
  }

  private static IndexService service(SqliteDatabase database) {
    return new IndexService(database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), sequence(100));
  }

  private static java.util.function.Supplier<UUID> sequence(long start) {
    AtomicLong counter = new AtomicLong(start);
    return () -> uuid(counter.incrementAndGet());
  }

  private static UUID uuid(long value) {
    return new UUID(0, value);
  }
}
