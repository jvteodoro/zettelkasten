package org.zettelkasten.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zettelkasten.application.AddressConflictException;
import org.zettelkasten.application.PersistenceException;
import org.zettelkasten.domain.AddressReference;
import org.zettelkasten.domain.AuthoredReference;
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibNote;
import org.zettelkasten.domain.BibliographicSource;
import org.zettelkasten.domain.BodyRun;
import org.zettelkasten.domain.CardAnnotation;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.CardState;
import org.zettelkasten.domain.DomainEvent;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.KeytermLinkRun;
import org.zettelkasten.domain.ListIndexCard;
import org.zettelkasten.domain.MainCard;
import org.zettelkasten.domain.NumericAlphaAddress;
import org.zettelkasten.domain.PlacementSnapshot;
import org.zettelkasten.domain.SourceExternalId;
import org.zettelkasten.domain.TextRun;

class SqlitePersistenceTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @TempDir Path temp;

  @Test
  void createAppliesChecksummedMigrationAndEveryConnectionEnablesForeignKeysAndWal()
      throws Exception {
    Path path = temp.resolve("slipbox.db");
    SqliteDatabase database = SqliteDatabase.create(path);
    assertTrue(Files.isRegularFile(path));
    assertEquals(3, database.schemaVersion());
    try (var connection = database.newConnection();
        Statement statement = connection.createStatement();
        var foreignKeys = statement.executeQuery("PRAGMA foreign_keys")) {
      assertTrue(foreignKeys.next());
      assertEquals(1, foreignKeys.getInt(1));
    }
    try (var connection = database.newConnection();
        Statement statement = connection.createStatement();
        var journalMode = statement.executeQuery("PRAGMA journal_mode")) {
      assertTrue(journalMode.next());
      assertEquals("wal", journalMode.getString(1).toLowerCase());
    }
    try (var connection = database.newConnection();
        Statement statement = connection.createStatement();
        var busyTimeout = statement.executeQuery("PRAGMA busy_timeout")) {
      assertTrue(busyTimeout.next());
      assertEquals(5_000, busyTimeout.getInt(1));
    }
    try (var connection = database.newConnection();
        Statement statement = connection.createStatement();
        var migration =
            statement.executeQuery("SELECT version, checksum_sha256 FROM schema_migrations")) {
      assertTrue(migration.next());
      assertEquals(1, migration.getInt(1));
      assertEquals(64, migration.getString(2).length());
      assertTrue(migration.next());
      assertEquals(2, migration.getInt(1));
      assertEquals(64, migration.getString(2).length());
      assertTrue(migration.next());
      assertEquals(3, migration.getInt(1));
      assertEquals(64, migration.getString(2).length());
      assertFalse(migration.next());
    }
    try (var connection = database.newConnection();
        Statement statement = connection.createStatement()) {
      assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("UPDATE schema_migrations SET description='tampered'"));
      assertThrows(
          SQLException.class, () -> statement.executeUpdate("DELETE FROM schema_migrations"));
    }
    assertThrows(PersistenceException.class, () -> SqliteDatabase.create(path));
  }

  @Test
  void diskReopenRoundTripsDraftInstallAndAppendOnlyAnnotations() throws Exception {
    Path path = temp.resolve("roundtrip.db");
    var database = SqliteDatabase.create(path);
    UUID cardId = uuid(1);
    List<BodyRun> originalRuns =
        List.<BodyRun>of(
            new TextRun(uuid(2), "Percepções — Überprüfung 漢字"),
            new CardLinkRun(uuid(3), "~/1/1a"),
            new KeytermLinkRun(uuid(4), "Argument"),
            new ExRefRun(uuid(5), "b.Source, xiv"));
    MainCard draft = MainCard.createDraft(cardId, NOW, originalRuns);
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.mainCards().insertDraft(draft);
              return null;
            });

    var changed = draft.editDraft(0, List.of(new TextRun(uuid(6), "Revised draft")));
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.mainCards().updateDraft(changed, 0);
              return null;
            });
    var address = NumericAlphaAddress.parse("3118/2A");
    Instant installedAt = NOW.plusSeconds(60);
    var installed = changed.install(address, installedAt);
    var placement = new PlacementSnapshot(cardId, address, null, null, installedAt);
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.mainCards().install(installed, placement, changed.revision());
              tx.mainCards()
                  .appendAnnotation(
                      cardId,
                      new CardAnnotation(
                          uuid(7),
                          NOW.plusSeconds(120),
                          List.of(new TextRun(uuid(8), "Anmerkung"))));
              return null;
            });

    var reopened = SqliteDatabase.open(path);
    var restored =
        reopened.unitOfWork().transact(tx -> tx.mainCards().findById(cardId).orElseThrow());
    assertEquals(CardState.INSTALLED, restored.state());
    assertEquals("3118/2A", restored.addressLiteral());
    assertEquals(1, restored.revision());
    assertEquals(1, restored.installedBodyRevision());
    assertEquals("Revised draft", restored.authoredBody().getFirst().literal());
    assertEquals("Anmerkung", restored.annotations().getFirst().runs().getFirst().literal());
    assertEquals(
        restored.id(),
        reopened
            .unitOfWork()
            .transact(tx -> tx.mainCards().findByAddress(address).orElseThrow().id()));
    assertEquals(
        List.of("CardCreated", "DraftEdited", "CardInstalled", "CardAnnotationAppended"),
        reopened
            .unitOfWork()
            .transact(
                tx -> tx.events().findAfter(0, 100).stream().map(DomainEvent::eventType).toList()));

    try (var connection = reopened.newConnection();
        var update =
            connection.prepareStatement(
                "UPDATE card_runs SET authored_literal='rewritten' WHERE id=?")) {
      update.setString(1, uuid(6).toString());
      assertThrows(SQLException.class, update::executeUpdate);
    }
    try (var connection = reopened.newConnection();
        Statement statement = connection.createStatement()) {
      assertThrows(
          SQLException.class,
          () ->
              statement.executeUpdate(
                  "UPDATE card_runs SET authored_literal='rewritten' WHERE id='" + uuid(8) + "'"));
      assertThrows(
          SQLException.class,
          () ->
              statement.executeUpdate(
                  "UPDATE placements SET raw_address='7/7' WHERE card_id='" + cardId + "'"));
      assertThrows(
          SQLException.class,
          () ->
              statement.executeUpdate(
                  "UPDATE cards SET revision=revision+1 WHERE id='" + cardId + "'"));
    }
    assertEquals(
        "Revised draft",
        reopened
            .unitOfWork()
            .transact(
                tx ->
                    tx.mainCards()
                        .findById(cardId)
                        .orElseThrow()
                        .authoredBody()
                        .getFirst()
                        .literal()));
  }

  @Test
  void typedPendingReferencesPersistWithTheirRunOccurrenceAndForeignKeysRejectOrphans() {
    var database = SqliteDatabase.create(temp.resolve("references.db"));
    UUID cardId = uuid(10);
    var run = new CardLinkRun(uuid(11), "/1");
    var card = MainCard.createDraft(cardId, NOW, List.of(run));
    var reference = AuthoredReference.card(uuid(12), cardId, run.id(), run.target(), NOW);
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.mainCards().insertDraft(card);
              tx.references().insert(reference);
              return null;
            });
    var restored =
        database.unitOfWork().transact(tx -> tx.references().findBySourceCard(cardId).getFirst());
    assertEquals(reference, restored);
    assertEquals("/1", restored.authoredLiteral());
    assertEquals(
        List.of("CardCreated", "ReferenceAuthored"),
        database
            .unitOfWork()
            .transact(
                tx -> tx.events().findAfter(0, 100).stream().map(DomainEvent::eventType).toList()));

    var orphan =
        AuthoredReference.card(uuid(13), uuid(99), uuid(98), AddressReference.parse("/2"), NOW);
    assertThrows(
        PersistenceException.class,
        () ->
            database
                .unitOfWork()
                .transact(
                    tx -> {
                      tx.references().insert(orphan);
                      return null;
                    }));
  }

  @Test
  void indexAndBibliographyAggregatesRoundTripInTheSameCanonicalStore() {
    var database = SqliteDatabase.create(temp.resolve("boxes.db"));
    var listOne = new ListIndexCard(uuid(60), 'a', 1, NOW);
    var listTwo = new ListIndexCard(uuid(61), 'A', 2, NOW.plusSeconds(1));
    var keyterm = new KeytermIndexCard(uuid(62), "Überblick", NOW.plusSeconds(2));
    Map<String, String> rawFields = new LinkedHashMap<>();
    rawFields.put("unknownfield", "preserve this raw value");
    rawFields.put("language", "de");
    var source =
        new BibliographicSource(
            uuid(63),
            "article",
            "Überprüfung der Theorie",
            "Müller, A.",
            "2024",
            "Zeitschrift für Forschung",
            "Verlag",
            "2",
            "17",
            "3",
            "xiv–xvi",
            "10.1234/example",
            null,
            null,
            rawFields,
            "@article{muller24, unknownfield={preserve this raw value}}",
            NOW,
            NOW.plusSeconds(3));
    var bibCard =
        new BibCard(uuid(64), source.id(), NOW.plusSeconds(4), "Warum?", "Kurzüberblick — 参照");
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.indexCards().insertListCard(listOne);
              tx.indexCards().insertListCard(listTwo);
              tx.indexCards().insertKeytermCard(keyterm);
              tx.bibliography().insertSource(source);
              tx.bibliography().insertBibCard(bibCard);
              return null;
            });

    var reopened = SqliteDatabase.open(database.path());
    var boxes =
        reopened
            .unitOfWork()
            .transact(
                tx ->
                    List.of(
                        tx.indexCards().findListCards('a'),
                        tx.indexCards().findKeytermCards(),
                        tx.bibliography().findSource(source.id()).orElseThrow(),
                        tx.bibliography().findBibCard(bibCard.id()).orElseThrow()));
    assertEquals(List.of(listOne, listTwo), boxes.getFirst());
    assertEquals(List.of(keyterm), boxes.get(1));
    assertEquals(source, boxes.get(2));
    assertEquals(bibCard, boxes.get(3));
    assertEquals("preserve this raw value", source.rawFields().get("unknownfield"));
    boolean keytermStored =
        reopened.unitOfWork().transact(tx -> tx.indexCards().contains(keyterm.id()));
    assertTrue(keytermStored);
    assertEquals(
        List.of(
            "IndexCardCreated",
            "IndexCardCreated",
            "IndexCardCreated",
            "BibliographicSourceCreated",
            "BibCardCreated"),
        reopened
            .unitOfWork()
            .transact(
                tx -> tx.events().findAfter(0, 100).stream().map(DomainEvent::eventType).toList()));
  }

  @Test
  void bibliographyNotesAliasesAndImportHistoryAreAppendOnly() throws Exception {
    var database = SqliteDatabase.create(temp.resolve("bib-append-only.db"));
    var source =
        new BibliographicSource(
            uuid(800),
            "book",
            "Title",
            "Author",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "@book{x,title={Title}}",
            NOW,
            NOW);
    var card = new BibCard(uuid(801), source.id(), NOW, "Goal", "Overview");
    var note =
        new BibNote(
            uuid(802), card.id(), 0, BibNote.LocatorType.PAGE, "xiv", "Observation", NOW, null);
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.bibliography().insertSource(source);
              tx.bibliography().insertBibCard(card);
              tx.bibliography().insertBibNote(note);
              tx.bibliography()
                  .insertSourceExternalId(
                      new SourceExternalId(uuid(803), source.id(), "BIBTEX_FILE", "scope", "x"));
              tx.bibliography()
                  .insertImportBatch(uuid(804), "scope", "x.bib", "a".repeat(64), "APPLIED", NOW);
              tx.bibliography()
                  .insertImportEntry(
                      uuid(805), uuid(804), source.id(), "x", source.rawRecord(), "NEW", "");
              return null;
            });

    try (var connection = database.newConnection();
        Statement statement = connection.createStatement()) {
      assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("UPDATE bibnotes SET locator_literal='lost'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM bibnotes"));
      assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("UPDATE source_external_ids SET external_id='changed'"));
      assertThrows(
          SQLException.class, () -> statement.executeUpdate("DELETE FROM source_external_ids"));
      assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("UPDATE import_batches SET status='REJECTED'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM import_batches"));
      assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("UPDATE import_entries SET outcome='ERROR'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM import_entries"));
    }
  }

  @Test
  void failedWorkAndDuplicateAddressRollBackDraftStatePlacementAndBody() {
    var database = SqliteDatabase.create(temp.resolve("rollback.db"));
    UUID firstId = uuid(20);
    UUID secondId = uuid(21);
    var first = MainCard.createDraft(firstId, NOW, List.of(new TextRun(uuid(22), "first")));
    var second = MainCard.createDraft(secondId, NOW, List.of(new TextRun(uuid(23), "second")));
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.mainCards().insertDraft(first);
              tx.mainCards().insertDraft(second);
              return null;
            });
    UUID rolledBackId = uuid(24);
    var faulted = MainCard.createDraft(rolledBackId, NOW);
    assertThrows(
        IllegalStateException.class,
        () ->
            database
                .unitOfWork()
                .transact(
                    tx -> {
                      tx.mainCards().insertDraft(faulted);
                      throw new IllegalStateException("injected transaction fault");
                    }));
    boolean rolledBackMissing =
        database.unitOfWork().transact(tx -> tx.mainCards().findById(rolledBackId).isEmpty());
    assertTrue(rolledBackMissing);
    int eventsAfterInjectedFailure =
        database.unitOfWork().transact(tx -> tx.events().findAfter(0, 100).size());
    assertEquals(2, eventsAfterInjectedFailure);

    assertThrows(
        PersistenceException.class,
        () ->
            database
                .unitOfWork()
                .transact(
                    tx -> {
                      tx.mainCards()
                          .appendAnnotation(
                              firstId,
                              new CardAnnotation(
                                  uuid(25),
                                  NOW.plusSeconds(5),
                                  List.of(new TextRun(uuid(26), "not installed"))));
                      return null;
                    }));

    install(database, first, "3118/2a");
    var secondInstalled = second.install("3118/2A", NOW.plusSeconds(30));
    var duplicatePlacement =
        new PlacementSnapshot(
            secondId,
            NumericAlphaAddress.parse("3118/2A"),
            firstId,
            null,
            secondInstalled.installedAt());
    assertThrows(
        AddressConflictException.class,
        () ->
            database
                .unitOfWork()
                .transact(
                    tx -> {
                      tx.mainCards()
                          .install(secondInstalled, duplicatePlacement, second.revision());
                      return null;
                    }));
    var retained =
        database.unitOfWork().transact(tx -> tx.mainCards().findById(secondId).orElseThrow());
    assertEquals(CardState.DRAFT, retained.state());
    assertEquals("second", retained.authoredBody().getFirst().literal());
    boolean firstAddressRetained =
        database
            .unitOfWork()
            .transact(
                tx ->
                    tx.mainCards().findByAddress(NumericAlphaAddress.parse("3118/2a")).isPresent());
    assertTrue(firstAddressRetained);
  }

  @Test
  void mutationEventsShareTransactionsReopenInSequenceAndRetainFutureVersions() throws Exception {
    AtomicLong identities = new AtomicLong();
    var database =
        SqliteDatabase.create(
            temp.resolve("events.db"),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> new UUID(0, identities.incrementAndGet()));
    UUID cardId = uuid(70);
    var draft = MainCard.createDraft(cardId, NOW, List.of(new TextRun(uuid(71), "Private note")));
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.mainCards().insertDraft(draft);
              return null;
            });
    var installed = draft.install("3118/2a", NOW.plusSeconds(1));
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.mainCards()
                  .install(
                      installed,
                      new PlacementSnapshot(
                          installed.id(), installed.address(), null, null, installed.installedAt()),
                      0);
              return null;
            });

    var reopened = SqliteDatabase.open(database.path());
    var events = reopened.unitOfWork().transact(tx -> tx.events().findAfter(0, 100));
    assertEquals(
        List.of("CardCreated", "CardInstalled"),
        events.stream().map(DomainEvent::eventType).toList());
    assertTrue(events.get(0).sequence() < events.get(1).sequence());
    assertEquals(cardId, events.get(0).aggregateId());
    assertEquals(1, events.get(0).schemaVersion());
    assertFalse(events.get(0).payloadJson().contains("Private note"));
    assertEquals(NOW, events.get(0).occurredAt());
    assertEquals(uuid(1), events.get(0).eventId());
    assertEquals(uuid(2), events.get(0).correlationId());
    assertTrue(events.get(1).payloadJson().contains("\"addressScheme\":\"ANTINET_SLASH_V1\""));

    try (var fixture =
        SqlitePersistenceTest.class.getResourceAsStream(
            "/fixtures/events/card-installed-v1.json")) {
      assertNotNull(fixture);
      String fixtureJson = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(fixtureJson.contains("\"schemaVersion\":1"));
      assertTrue(fixtureJson.contains("\"predecessorId\":null"));
      assertTrue(fixtureJson.contains("\"eventType\":\"CardInstalled\""));
    }

    String futurePayload = "{\"schemaVersion\":42,\"extension\":\"preserve\"}";
    try (var connection = reopened.newConnection();
        var insert =
            connection.prepareStatement(
                "INSERT INTO domain_events(event_id,aggregate_type,aggregate_id,event_type,schema_version,"
                    + "happened_at,correlation_id,payload_json) VALUES(?,?,?,?,?,?,?,?)")) {
      insert.setString(1, uuid(72).toString());
      insert.setString(2, "MAIN_CARD");
      insert.setString(3, cardId.toString());
      insert.setString(4, "FutureEvent");
      insert.setInt(5, 42);
      insert.setString(6, NOW.toString());
      insert.setString(7, uuid(73).toString());
      insert.setString(8, futurePayload);
      insert.executeUpdate();
    }
    var retained = reopened.unitOfWork().transact(tx -> tx.events().findAfter(2, 10).getFirst());
    assertEquals(42, retained.schemaVersion());
    assertEquals(futurePayload, retained.payloadJson());
  }

  @Test
  void simulatedCommitFailureRollsBackAggregateAndItsEvent() {
    var database = SqliteDatabase.create(temp.resolve("commit-failure.db"));
    var failingUnitOfWork =
        new SqliteUnitOfWork(
            database,
            connection -> {
              throw new SQLException("simulated commit failure");
            });
    UUID cardId = uuid(74);
    var draft = MainCard.createDraft(cardId, NOW);
    assertThrows(
        PersistenceException.class,
        () ->
            failingUnitOfWork.transact(
                tx -> {
                  tx.mainCards().insertDraft(draft);
                  return null;
                }));

    var counts =
        database
            .unitOfWork()
            .transact(
                tx ->
                    List.of(
                        tx.mainCards().findById(cardId).isPresent(),
                        tx.events().findAfter(0, 10).size()));
    assertEquals(List.of(false, 0), counts);
  }

  @Test
  void concurrentAttemptsForOneAddressHaveExactlyOneWinner() throws Exception {
    var database = SqliteDatabase.create(temp.resolve("concurrent.db"));
    var first = MainCard.createDraft(uuid(30), NOW);
    var second = MainCard.createDraft(uuid(31), NOW.plusSeconds(1));
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.mainCards().insertDraft(first);
              tx.mainCards().insertDraft(second);
              return null;
            });
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var one = executor.submit(() -> concurrentInstall(database, first, ready, start));
      var two = executor.submit(() -> concurrentInstall(database, second, ready, start));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      boolean resultOne = one.get(10, TimeUnit.SECONDS);
      boolean resultTwo = two.get(10, TimeUnit.SECONDS);
      assertTrue(resultOne ^ resultTwo);
    }
    int installedCount =
        database.unitOfWork().transact(tx -> tx.mainCards().findInstalledInAddressOrder().size());
    int draftCount = database.unitOfWork().transact(tx -> tx.mainCards().findDrafts().size());
    assertEquals(1, installedCount);
    assertEquals(1, draftCount);
  }

  @Test
  void openingAnOlderFixtureAppliesTheNextMigrationWithoutOverwritingIt() throws Exception {
    Path path = temp.resolve("v0-fixture.db");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement statement = connection.createStatement()) {
      String fixture;
      try (var input = getClass().getResourceAsStream("/fixtures/db/v0-empty.sql")) {
        assertNotNull(input);
        fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
      for (String sql : fixture.split(";")) {
        if (!sql.isBlank()) statement.execute(sql);
      }
      statement.execute("PRAGMA user_version = 0");
    }
    var migrated = SqliteDatabase.open(path);
    assertEquals(3, migrated.schemaVersion());
    boolean noDrafts = migrated.unitOfWork().transact(tx -> tx.mainCards().findDrafts().isEmpty());
    assertTrue(noDrafts);
    try (var connection = migrated.newConnection();
        Statement statement = connection.createStatement();
        var row = statement.executeQuery("SELECT note FROM legacy_fixture")) {
      assertTrue(row.next());
      assertEquals("preserve existing data", row.getString(1));
    }
  }

  @Test
  void openDoesNotSilentlyCreateAnUnspecifiedDatabase() {
    Path missing = temp.resolve("missing.db");
    assertThrows(PersistenceException.class, () -> SqliteDatabase.open(missing));
    assertFalse(Files.exists(missing));
  }

  private static boolean concurrentInstall(
      SqliteDatabase database, MainCard draft, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
    try {
      install(database, draft, "7777/1");
      return true;
    } catch (AddressConflictException expected) {
      return false;
    }
  }

  private static void install(SqliteDatabase database, MainCard draft, String literal) {
    var address = NumericAlphaAddress.parse(literal);
    Instant time = draft.createdAt().plusSeconds(100);
    var installed = draft.install(address, time);
    var placement = new PlacementSnapshot(draft.id(), address, null, null, time);
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.mainCards().install(installed, placement, draft.revision());
              return null;
            });
  }

  private static UUID uuid(long value) {
    return new UUID(0, value);
  }
}
