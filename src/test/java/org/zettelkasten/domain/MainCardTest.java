package org.zettelkasten.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MainCardTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  private static final UUID CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void draftStartsUnplacedAndEditsWithRevisionCheck() {
    var draft = MainCard.createDraft(CARD_ID, NOW);
    assertEquals(CardState.DRAFT, draft.state());
    assertEquals(0, draft.revision());
    assertEquals(null, draft.addressLiteral());
    var edited = draft.editDraft(0, List.of(new TextRun(uuid(1), "Percepções — Überprüfung 漢字")));
    assertEquals(1, edited.revision());
    assertEquals("Percepções — Überprüfung 漢字", edited.authoredBody().getFirst().literal());
    assertThrows(StaleDraftRevisionException.class, () -> edited.editDraft(0, List.of()));
    assertThrows(UnsupportedOperationException.class, () -> edited.authoredBody().clear());
  }

  @Test
  void installationFreezesOriginalAndAnnotationsOnlyAppend() {
    var original = new TextRun(uuid(2), "Original body");
    var draft = MainCard.createDraft(CARD_ID, NOW, List.of(original));
    var installed = draft.install("3118/2a", NOW.plusSeconds(10));
    assertEquals(CardState.INSTALLED, installed.state());
    assertEquals(0, installed.installedBodyRevision());
    assertEquals("3118/2a", installed.addressLiteral());
    assertThrows(IllegalStateException.class, () -> installed.editDraft(0, List.of()));
    var annotation =
        new CardAnnotation(
            uuid(3), NOW.plusSeconds(20), List.of(new TextRun(uuid(4), "Correction")));
    var amended = installed.appendAnnotation(annotation);
    assertNotSame(installed, amended);
    assertEquals(List.of(original), amended.authoredBody());
    assertEquals(List.of(annotation), amended.annotations());
    assertEquals(0, amended.revision());
  }

  @Test
  void repeatedReferenceRunsRemainDistinctAuthoredOccurrences() {
    var first = new CardLinkRun(uuid(10), "3118/2a");
    var second = new CardLinkRun(uuid(11), "3118/2a");
    var card = MainCard.createDraft(CARD_ID, NOW, List.of(first, second));
    assertEquals(2, card.authoredBody().size());
    assertEquals(first.literal(), second.literal());
    assertNotSame(first.id(), second.id());
  }

  @Test
  void annotationCannotReuseOriginalOrEarlierRunIdentity() {
    var run = new TextRun(uuid(20), "Original");
    var installed = MainCard.createDraft(CARD_ID, NOW, List.of(run)).install("1/1", NOW);
    var annotation =
        new CardAnnotation(uuid(21), NOW, List.of(new TextRun(run.id(), "invalid replacement")));
    assertThrows(IllegalArgumentException.class, () -> installed.appendAnnotation(annotation));
  }

  @Test
  void installationRejectsAnInvalidAddressWithoutChangingTheDraft() {
    var draft = MainCard.createDraft(CARD_ID, NOW, List.of(new TextRun(uuid(30), "still here")));
    assertThrows(AddressParseException.class, () -> draft.install("not/a-valid-address", NOW));
    assertEquals(CardState.DRAFT, draft.state());
    assertEquals("still here", draft.authoredBody().getFirst().literal());
  }

  private static UUID uuid(long tail) {
    return new UUID(0, tail);
  }
}
