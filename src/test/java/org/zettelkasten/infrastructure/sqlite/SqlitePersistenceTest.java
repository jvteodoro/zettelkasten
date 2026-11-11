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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zettelkasten.application.AddressConflictException;
import org.zettelkasten.application.PersistenceException;
import org.zettelkasten.domain.AddressReference;
import org.zettelkasten.domain.AuthoredReference;
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibliographicSource;
import org.zettelkasten.domain.BodyRun;
import org.zettelkasten.domain.CardAnnotation;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.CardState;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.KeytermLinkRun;
import org.zettelkasten.domain.ListIndexCard;
import org.zettelkasten.domain.MainCard;
import org.zettelkasten.domain.NumericAlphaAddress;
import org.zettelkasten.domain.PlacementSnapshot;
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
    assertEquals(1, database.schemaVersion());
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
    assertEquals(1, migrated.schemaVersion());
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
