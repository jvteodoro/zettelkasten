package org.zettelkasten.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoxCardIdentityTest {
  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @Test
  void indexCardsHaveStableIdsAndValidateTheirPhysicalDivider() {
    var list = new ListIndexCard(ID, 'a', 2, NOW);
    assertEquals('A', list.divider());
    assertEquals(2, list.overflowNumber());
    var term = new KeytermIndexCard(ID, "Überblick", NOW);
    assertEquals("Überblick", term.keyterm());
    assertThrows(IllegalArgumentException.class, () -> new ListIndexCard(ID, '#', 1, NOW));
    assertThrows(IllegalArgumentException.class, () -> new KeytermIndexCard(ID, " ", NOW));
  }

  @Test
  void bibliographyResearchCardIsDistinctFromItsSourceAndPreservesUnicode() {
    var sourceId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    var bib = new BibCard(ID, sourceId, NOW, "Warum wirkt das?", "Überblick — 参照");
    assertEquals(ID, bib.id());
    assertEquals(sourceId, bib.sourceId());
    assertEquals("Überblick — 参照", bib.sourceOverview());
  }
}
