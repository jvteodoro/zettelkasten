package org.zettelkasten.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zettelkasten.domain.BibNote;
import org.zettelkasten.domain.BibliographicSource;
import org.zettelkasten.infrastructure.bibtex.JBibTeXFileParser;
import org.zettelkasten.infrastructure.sqlite.SqliteDatabase;

class BibliographyServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @TempDir java.nio.file.Path temp;

  @Test
  void dryRunApplyAndReimportAreIdempotentAndDoNotInventResearchNotes() {
    var database = database("import.db");
    var service = service(database);
    String file =
        """
        @string{venue = {Local Research Review}}
        @article{garcia2026,
          author = {García, José},
          title = {An {Analog} Argument},
          journal = venue,
          year = {2026},
          customfield = {保留}
        }
        """;

    var dryRun = service.dryRunImport("library-one", "sources.bib", file);
    assertEquals(BibTeXImportResult.Status.DRY_RUN, dryRun.status());
    assertEquals(BibTeXImportResult.Outcome.NEW, dryRun.entries().getFirst().outcome());
    assertTrue(service.getSources().isEmpty());

    var applied = service.applyImport("library-one", "sources.bib", file);
    assertEquals(BibTeXImportResult.Status.APPLIED, applied.status());
    assertEquals(1, service.getSources().size());
    var imported = service.getSources().getFirst();
    assertEquals("García, José", imported.creatorsText());
    assertEquals("保留", imported.rawFields().get("customfield"));
    assertEquals(file, imported.rawRecord());
    var researchCard = service.createBibCard(imported.id(), "Read manually", "Local overview");
    var authoredNote =
        service.addBibNote(researchCard.id(), BibNote.LocatorType.PAGE, "xiv", "User observation");

    var repeated = service.applyImport("library-one", "sources.bib", file);
    assertEquals(BibTeXImportResult.Status.APPLIED, repeated.status());
    assertEquals(BibTeXImportResult.Outcome.UNCHANGED, repeated.entries().getFirst().outcome());
    assertEquals(imported.id(), repeated.entries().getFirst().sourceId());
    assertEquals(1, service.getSources().size());
    assertEquals(List.of(authoredNote), service.getBibNotes(researchCard.id()));
    long authoredCardEvents =
        database
            .unitOfWork()
            .transact(
                tx ->
                    tx.events().findAfter(0, 100).stream()
                        .filter(event -> event.eventType().equals("BibCardCreated"))
                        .count());
    assertEquals(1L, authoredCardEvents);
  }

  @Test
  void namespacedCollisionsChangedRecordsAndDoiConflictsAreVisibleAndAtomic() {
    var database = database("collisions.db");
    var service = service(database);
    String original = "@book{same, title={First}, doi={10.1/example}}";
    var initial = service.applyImport("file-a", "a.bib", original);
    assertEquals(BibTeXImportResult.Status.APPLIED, initial.status());

    var otherNamespace = service.applyImport("file-b", "b.bib", "@book{same, title={Other}} ");
    assertEquals(BibTeXImportResult.Status.APPLIED, otherNamespace.status());
    assertEquals(2, service.getSources().size());

    var changed =
        service.applyImport("file-a", "a.bib", "@book{same, title={Changed}, doi={10.1/example}}");
    assertEquals(BibTeXImportResult.Status.REJECTED, changed.status());
    assertEquals(BibTeXImportResult.Outcome.CHANGED, changed.entries().getFirst().outcome());
    assertEquals(
        "First",
        service.getSources().stream()
            .filter(source -> source.id().equals(initial.entries().getFirst().sourceId()))
            .findFirst()
            .orElseThrow()
            .title());

    String mixed =
        "@book{would_be_new, title={Should roll back}}\n"
            + "@article{doi_copy, title={Duplicate DOI}, doi={10.1/example}}";
    var rejected = service.applyImport("file-c", "c.bib", mixed);
    assertEquals(BibTeXImportResult.Status.REJECTED, rejected.status());
    assertEquals(BibTeXImportResult.Outcome.NEW, rejected.entries().getFirst().outcome());
    assertNull(rejected.entries().getFirst().sourceId());
    assertEquals(BibTeXImportResult.Outcome.CONFLICT, rejected.entries().get(1).outcome());
    assertEquals(2, service.getSources().size());
  }

  @Test
  void malformedFilesAreRecordedAsRejectedWithoutPartialSources() {
    var database = database("errors.db");
    var service = service(database);
    var malformed = service.applyImport("bad", "bad.bib", "@book{broken, title={");
    assertEquals(BibTeXImportResult.Status.REJECTED, malformed.status());
    assertEquals(BibTeXImportResult.Outcome.ERROR, malformed.entries().getFirst().outcome());
    assertEquals(0, service.getSources().size());
  }

  @Test
  void manualSourcesAndOrderedLiteralBibNoteLocatorsRoundTrip() {
    var database = database("notes.db");
    var service = service(database);
    var source =
        service.createSource(
            new BibliographicSource(
                uuid(700),
                "book",
                "Manual source",
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
                java.util.Map.of("custom", "kept"),
                "manual raw record",
                NOW,
                NOW));
    var card = service.createBibCard(source.id(), "Read for concepts", "Overview authored locally");
    var page = service.addBibNote(card.id(), BibNote.LocatorType.PAGE, "xiv", "Opening note");
    var time = service.addBibNote(card.id(), BibNote.LocatorType.TIME, "00:32:18", "A later note");

    var reopenedDatabase = SqliteDatabase.open(temp.resolve("notes.db"));
    var reopened = service(reopenedDatabase);
    assertEquals(List.of(page, time), reopened.getBibNotes(card.id()));
    assertEquals(
        "xiv",
        reopenedDatabase
            .unitOfWork()
            .transact(tx -> tx.bibliography().findBibNotes(card.id()).getFirst().locatorLiteral()));
    assertEquals(
        "00:32:18",
        reopenedDatabase
            .unitOfWork()
            .transact(tx -> tx.bibliography().findBibNotes(card.id()).getLast().locatorLiteral()));
    assertFalse(source.rawFields().isEmpty());
  }

  @Test
  void explicitlyAttachedCitationAliasKeepsTheSourceIdentityAcrossKeyRename() {
    var service = service(database("aliases.db"));
    var source =
        service.createSource(
            new BibliographicSource(
                uuid(900),
                "book",
                "Retained title",
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
                java.util.Map.of("title", "Retained title"),
                "manual record",
                NOW,
                NOW));
    service.addExternalAlias(source.id(), "BIBTEX_FILE", "my-library", "oldkey");
    service.addExternalAlias(source.id(), "BIBTEX_FILE", "my-library", "newkey");

    var imported =
        service.applyImport("my-library", "renamed.bib", "@book{newkey, title={Retained title}}");
    assertEquals(BibTeXImportResult.Status.APPLIED, imported.status());
    assertEquals(BibTeXImportResult.Outcome.UNCHANGED, imported.entries().getFirst().outcome());
    assertEquals(source.id(), imported.entries().getFirst().sourceId());
    assertEquals(1, service.getSources().size());
  }

  private SqliteDatabase database(String filename) {
    return SqliteDatabase.create(temp.resolve(filename), Clock.fixed(NOW, ZoneOffset.UTC), ids(1));
  }

  private BibliographyService service(SqliteDatabase database) {
    return new BibliographyService(
        database.unitOfWork(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        ids(10_000),
        new JBibTeXFileParser());
  }

  private static java.util.function.Supplier<UUID> ids(long start) {
    AtomicLong next = new AtomicLong(start);
    return () -> new UUID(0, next.incrementAndGet());
  }

  private static UUID uuid(long value) {
    return new UUID(0, value);
  }
}
