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
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibNote;
import org.zettelkasten.domain.BibliographicSource;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.IndexEntry;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.ListIndexCard;
import org.zettelkasten.domain.MainAddressTarget;
import org.zettelkasten.domain.TextRun;

class GraphProjectionExporterTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  @TempDir Path temp;

  @Test
  void projectionIsDeterministicReversibleAndKeepsSemanticAndFilingEdgesDistinct()
      throws Exception {
    var database = SqliteDatabase.create(temp.resolve("canonical.db"));
    var ids = ids();
    var cards = new MainCardService(database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), ids);
    var draft =
        cards.createDraft(
            List.of(
                new TextRun(uuid(100), "A Unicode card — 漢字"),
                new CardLinkRun(uuid(101), "8/1"),
                new CardLinkRun(uuid(102), "8/1")));
    cards.installMainCard(draft.id(), draft.revision(), "8/2");
    var neighbor = cards.createDraft(List.of(new TextRun(uuid(103), "Neighbor card.")));
    cards.installMainCard(neighbor.id(), neighbor.revision(), "8/3");
    var source =
        new BibliographicSource(
            uuid(110),
            "article",
            "Source title",
            "Ada Author",
            "2024",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.Map.of(),
            "@article{a}",
            NOW,
            NOW);
    var list = new ListIndexCard(uuid(111), 'A', 1, NOW);
    database
        .unitOfWork()
        .transact(
            tx -> {
              tx.bibliography().insertSource(source);
              tx.indexCards().insertListCard(list);
              var sourceEntry =
                  tx.indexCards()
                      .appendEntry(
                          new IndexEntry(
                              uuid(112),
                              list.id(),
                              0,
                              "A pointer",
                              new MainAddressTarget("8/2", draft.id()),
                              NOW,
                              null));
              var keyterm = new KeytermIndexCard(uuid(118), "A pointer", NOW);
              tx.indexCards().insertKeytermCard(keyterm);
              var promotedEntry =
                  tx.indexCards()
                      .appendEntry(
                          new IndexEntry(
                              uuid(119),
                              keyterm.id(),
                              0,
                              "A pointer",
                              new MainAddressTarget("8/2", draft.id()),
                              NOW,
                              null));
              tx.indexCards().recordPromotion(sourceEntry, promotedEntry, keyterm, NOW);
              var bibCard = new BibCard(uuid(115), source.id(), NOW, "Goal", "Overview");
              tx.bibliography().insertBibCard(bibCard);
              tx.bibliography()
                  .insertBibNote(
                      new BibNote(
                          uuid(116),
                          bibCard.id(),
                          0,
                          BibNote.LocatorType.PAGE,
                          "9",
                          "A source note.",
                          NOW,
                          draft.id()));
              return null;
            });

    try (var connection = database.newConnection();
        var delete = connection.prepareStatement("DELETE FROM refs WHERE source_run_id=?")) {
      delete.setString(1, uuid(101).toString());
      assertEquals(1, delete.executeUpdate());
    }

    Path first = new GraphProjectionExporter(database).export(temp.resolve("graph-one"));
    Path second = new GraphProjectionExporter(database).export(temp.resolve("graph-two"));
    String graph = Files.readString(first.resolve("graph.jsonl"), StandardCharsets.UTF_8);
    String manifest = Files.readString(first.resolve("manifest.json"), StandardCharsets.UTF_8);
    assertEquals(graph, Files.readString(second.resolve("graph.jsonl"), StandardCharsets.UTF_8));
    assertEquals(
        manifest, Files.readString(second.resolve("manifest.json"), StandardCharsets.UTF_8));
    assertEquals(
        2,
        graph.lines().filter(line -> line.contains("\"namespace\":\"AUTHORED_REFERENCE\"")).count(),
        graph);
    assertTrue(graph.contains("\"targetLiteral\":\"8/1\""));
    assertTrue(graph.contains("\"resolutionStatus\":\"PENDING\""));
    assertTrue(graph.contains("\"namespace\":\"AUTHORED_INDEX_POINTER\""));
    assertTrue(graph.contains("\"namespace\":\"AUTHORED_INDEX_PROMOTION\""));
    assertTrue(graph.contains("\"namespace\":\"AUTHORED_BIBLIOGRAPHY_ASSOCIATION\""));
    assertTrue(graph.contains("\"namespace\":\"DERIVED_BIBLIOGRAPHIC_CONTEXT\",\"derived\":true"));
    assertTrue(graph.contains("\"namespace\":\"AUTHORED_REFERENCE\""));
    assertTrue(graph.contains("\"sourceRunId\":\"run:" + uuid(101) + "\""));
    assertTrue(graph.contains("\"namespace\":\"FILED_ADJACENT_TO\",\"derived\":true"));
    assertFalse(graph.contains("PARENT_OF"));
    assertTrue(graph.contains("\"record\":\"event\""));
    assertTrue(manifest.contains("\"format\" : \"zettelkasten-graph-jsonl\""));

    var report = new ArchiveOperations(SqliteDatabase.open(database.path())).doctor();
    assertTrue(report.healthy());
    assertThrows(
        org.zettelkasten.application.PersistenceException.class,
        () -> new GraphProjectionExporter(database).export(first));
  }

  private java.util.function.Supplier<UUID> ids() {
    AtomicLong value = new AtomicLong(1_000);
    return () -> new UUID(0, value.incrementAndGet());
  }

  private static UUID uuid(long value) {
    return new UUID(0, value);
  }
}
