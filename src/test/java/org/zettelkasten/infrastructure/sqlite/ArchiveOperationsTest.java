package org.zettelkasten.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zettelkasten.application.MainCardService;
import org.zettelkasten.domain.AuthoredReference;
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibNote;
import org.zettelkasten.domain.BibliographicSource;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.domain.IndexEntry;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.ListIndexCard;
import org.zettelkasten.domain.MainAddressTarget;
import org.zettelkasten.domain.SourceExternalId;
import org.zettelkasten.domain.TextRun;

class ArchiveOperationsTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @TempDir java.nio.file.Path temp;

  @Test
  void walBackupAndPortableRoundTripPreserveCardsAndDeterministicExport() throws Exception {
    var source = SqliteDatabase.create(temp.resolve("source.db"));
    var service = new MainCardService(source.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), ids());
    var link = new CardLinkRun(uuid(201), "/1");
    var external = new ExRefRun(uuid(202), "b.paper, p. 27");
    var draft =
        service.createDraft(List.of(new TextRun(uuid(200), "Überprüfung — 漢字"), link, external));
    service.installMainCard(draft.id(), draft.revision(), "3118/2a");
    service.appendAnnotation(draft.id(), List.of(new TextRun(uuid(203), "An appended note.")));
    service.createDraft(List.of(new TextRun(uuid(204), "A separate working draft.")));
    var sourceRecord =
        new BibliographicSource(
            uuid(210),
            "article",
            "Reading — 手書き",
            "Ada Müller",
            "2024",
            null,
            null,
            null,
            null,
            null,
            "27",
            null,
            null,
            null,
            java.util.Map.of("abstract", "Imported only"),
            "@article{paper,title={Reading}}",
            NOW,
            NOW);
    var bibCard = new BibCard(uuid(211), sourceRecord.id(), NOW, "Argument", "Authored overview");
    source
        .unitOfWork()
        .transact(
            tx -> {
              var listCard = new ListIndexCard(uuid(212), 'A', 1, NOW);
              tx.indexCards().insertListCard(listCard);
              var sourceEntry =
                  tx.indexCards()
                      .appendEntry(
                          new IndexEntry(
                              uuid(213),
                              listCard.id(),
                              0,
                              "Überprüfung",
                              new MainAddressTarget("3118/2a", draft.id()),
                              NOW,
                              null));
              var keyterm = new KeytermIndexCard(uuid(220), "Überprüfung", NOW);
              tx.indexCards().insertKeytermCard(keyterm);
              var promotedEntry =
                  tx.indexCards()
                      .appendEntry(
                          new IndexEntry(
                              uuid(221),
                              keyterm.id(),
                              0,
                              "Überprüfung",
                              new MainAddressTarget("3118/2a", draft.id()),
                              NOW,
                              null));
              tx.indexCards().recordPromotion(sourceEntry, promotedEntry, keyterm, NOW);
              tx.bibliography().insertSource(sourceRecord);
              tx.bibliography().insertBibCard(bibCard);
              tx.bibliography()
                  .insertBibNote(
                      new BibNote(
                          uuid(214),
                          bibCard.id(),
                          0,
                          BibNote.LocatorType.PAGE,
                          "27",
                          "User observation",
                          NOW,
                          null));
              tx.bibliography()
                  .insertSourceExternalId(
                      new SourceExternalId(
                          uuid(215), sourceRecord.id(), "BIBTEX_FILE", "papers", "paper"));
              tx.bibliography()
                  .insertImportBatch(
                      uuid(216), "papers", "papers.bib", "a".repeat(64), "APPLIED", NOW);
              tx.bibliography()
                  .insertImportEntry(
                      uuid(217),
                      uuid(216),
                      sourceRecord.id(),
                      "paper",
                      sourceRecord.rawRecord(),
                      "NEW",
                      "");
              tx.references()
                  .insert(
                      AuthoredReference.card(uuid(218), draft.id(), link.id(), link.target(), NOW));
              tx.references()
                  .insert(
                      AuthoredReference.external(
                          uuid(219), draft.id(), external.id(), "b.paper, p. 27", NOW));
              return null;
            });
    assertTrue(new ArchiveOperations(source).doctor().healthy());

    var operations = new ArchiveOperations(source);
    Path walPath = source.path().resolveSibling(source.path().getFileName() + "-wal");
    Path backupPath;
    try (var reader = DriverManagerConnections.open(source.path())) {
      reader.setAutoCommit(false);
      try (var statement = reader.createStatement();
          var result = statement.executeQuery("SELECT count(*) FROM cards")) {
        assertTrue(result.next());
      }
      service.createDraft(List.of(new TextRun(uuid(222), "Committed while a reader holds WAL.")));
      assertTrue(Files.isRegularFile(walPath));
      assertTrue(Files.size(walPath) > 0);
      backupPath = operations.backup(temp.resolve("backup.db"));
    }
    assertTrue(new ArchiveOperations(SqliteDatabase.open(backupPath)).doctor().healthy());
    assertEquals(
        1,
        new MainCardService(SqliteDatabase.open(backupPath).unitOfWork(), Clock.systemUTC(), ids())
            .getInstalledInAddressOrder()
            .size());
    var sourceExport = operations.export(temp.resolve("source-export"));
    var backupExport =
        new ArchiveOperations(SqliteDatabase.open(backupPath))
            .export(temp.resolve("backup-export"));
    for (String name :
        List.of(
            "cards.jsonl",
            "placements.jsonl",
            "references.jsonl",
            "index.jsonl",
            "bibliography.jsonl",
            "events.jsonl")) {
      assertTrue(
          java.util.Arrays.equals(
              Files.readAllBytes(sourceExport.resolve(name)),
              Files.readAllBytes(backupExport.resolve(name))),
          "WAL backup changed " + name);
    }

    var firstExport = operations.export(temp.resolve("export-one"));
    var secondExport = operations.export(temp.resolve("export-two"));
    for (String name :
        List.of(
            "cards.jsonl",
            "placements.jsonl",
            "references.jsonl",
            "index.jsonl",
            "bibliography.jsonl",
            "events.jsonl",
            "manifest.json")) {
      assertTrue(
          java.util.Arrays.equals(
              Files.readAllBytes(firstExport.resolve(name)),
              Files.readAllBytes(secondExport.resolve(name))),
          name);
    }

    var imported = ArchiveOperations.importArchive(firstExport, temp.resolve("imported.db"));
    var importedCard =
        new MainCardService(imported.unitOfWork(), Clock.systemUTC(), ids())
            .getMainByExactAddress("3118/2a")
            .orElseThrow();
    assertEquals("Überprüfung — 漢字", importedCard.authoredBody().getFirst().literal());
    assertTrue(
        imported.unitOfWork().transact(tx -> tx.indexCards().findListCards('A').size()) == 1);
    assertTrue(
        imported.unitOfWork().transact(tx -> tx.indexCards().findKeytermCards().size()) == 1);
    assertTrue(
        imported.unitOfWork().transact(tx -> tx.bibliography().findBibNotes(bibCard.id()).size())
            == 1);
    assertTrue(
        imported.unitOfWork().transact(tx -> tx.references().findBySourceCard(draft.id()).size())
            == 2);
    assertTrue(new ArchiveOperations(imported).doctor().healthy());
    var reExport = new ArchiveOperations(imported).export(temp.resolve("export-round-trip"));
    for (String name :
        List.of(
            "cards.jsonl",
            "placements.jsonl",
            "references.jsonl",
            "index.jsonl",
            "bibliography.jsonl",
            "events.jsonl",
            "manifest.json")) {
      assertTrue(
          java.util.Arrays.equals(
              Files.readAllBytes(firstExport.resolve(name)),
              Files.readAllBytes(reExport.resolve(name))),
          "canonical round trip changed " + name);
    }
  }

  @Test
  void invalidChecksumIsRejectedBeforeDestinationIsCreatedAndOverwriteIsRefused() throws Exception {
    var source = SqliteDatabase.create(temp.resolve("source.db"));
    var exported = new ArchiveOperations(source).export(temp.resolve("export"));
    var corrupt = temp.resolve("corrupt");
    try (var files = Files.walk(exported)) {
      for (var file : files.toList()) {
        var target = corrupt.resolve(exported.relativize(file));
        if (Files.isDirectory(file)) Files.createDirectories(target);
        else Files.copy(file, target);
      }
    }
    Files.writeString(corrupt.resolve("events.jsonl"), "{}\n", StandardCharsets.UTF_8);
    var destination = temp.resolve("never-created.db");
    assertThrows(
        RuntimeException.class, () -> ArchiveOperations.importArchive(corrupt, destination));
    assertFalse(Files.exists(destination));
    assertThrows(RuntimeException.class, () -> new ArchiveOperations(source).backup(source.path()));
  }

  @Test
  void doctorReportsForeignKeyViolationsWhenPresent() throws Exception {
    var database = SqliteDatabase.create(temp.resolve("bad.db"));
    try (var connection = database.newConnection();
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=OFF");
      statement.executeUpdate(
          "INSERT INTO source_external_ids(id,source_id,provider,scope,external_id) VALUES('x','missing','p','s','e')");
    }
    var report = new ArchiveOperations(database).doctor();
    assertFalse(report.healthy());
    assertTrue(
        report.problems().stream().anyMatch(problem -> problem.startsWith("foreign_key_check")));
  }

  private static java.util.function.Supplier<UUID> ids() {
    AtomicLong value = new AtomicLong(100);
    return () -> uuid(value.incrementAndGet());
  }

  private static UUID uuid(long low) {
    return new UUID(0, low);
  }
}
