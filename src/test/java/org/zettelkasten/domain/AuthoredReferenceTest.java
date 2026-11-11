package org.zettelkasten.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoredReferenceTest {
  private static final UUID SOURCE_CARD = uuid(1);
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @Test
  void stemBranchAndFullAddressLiteralsResolveOnlyFromExplicitContext() {
    var source = NumericAlphaAddress.parse("4214/2e/3a");
    var stem = AddressReference.parse("/1");
    var branch = AddressReference.parse("~/1/1A");
    var full = AddressReference.parse("1337/1B");
    assertEquals(AddressReference.Kind.STEM, stem.kind());
    assertEquals(AddressReference.Kind.BRANCH, branch.kind());
    assertEquals(AddressReference.Kind.FULL, full.kind());
    assertEquals("/1", stem.literal());
    assertEquals("4214/2e/3a/1", stem.resolve(source).orElseThrow().canonical());
    assertEquals("4214/1/1a", branch.resolve(source).orElseThrow().canonical());
    assertEquals("1337/1b", full.resolve(null).orElseThrow().canonical());
    assertEquals(Optional.empty(), stem.resolve(null));
  }

  @Test
  void malformedRelativeLiteralsAreRejectedWithoutNormalizingThem() {
    for (String literal : new String[] {"/", "~/", "~bad", " /1", "~/01", "1//2"}) {
      assertThrows(AddressParseException.class, () -> AddressReference.parse(literal), literal);
    }
  }

  @Test
  void repeatedAuthoredOccurrencesKeepDistinctIdsAndLiteralRunIdentity() {
    var firstRun = new CardLinkRun(uuid(2), "/1");
    var secondRun = new CardLinkRun(uuid(3), "/1");
    var first = AuthoredReference.card(uuid(4), SOURCE_CARD, firstRun.id(), firstRun.target(), NOW);
    var second =
        AuthoredReference.card(uuid(5), SOURCE_CARD, secondRun.id(), secondRun.target(), NOW);
    assertEquals(ReferenceKind.STEM, first.kind());
    assertEquals("/1", first.authoredLiteral());
    assertEquals("/1", first.targetLiteral());
    assertEquals(firstRun.id(), first.sourceRunId());
    assertEquals(ResolutionStatus.PENDING, first.status());
    assertFalse(first.id().equals(second.id()));
    assertFalse(first.sourceRunId().equals(second.sourceRunId()));
  }

  @Test
  void cardIndexAndBibliographyReferencesResolveToOnlyTheirOwnTargetType() {
    var card =
        AuthoredReference.card(uuid(10), SOURCE_CARD, uuid(11), AddressReference.parse("1/2"), NOW);
    var resolvedCard = card.resolveCard(uuid(12));
    assertEquals(ResolutionStatus.RESOLVED, resolvedCard.status());
    assertEquals(uuid(12), resolvedCard.targetCardId());
    assertEquals("1/2", resolvedCard.authoredLiteral());
    assertNull(resolvedCard.targetIndexId());

    var keyterm = AuthoredReference.keyterm(uuid(20), SOURCE_CARD, uuid(21), "Überblick", NOW);
    var resolvedKeyterm = keyterm.resolveIndex(uuid(22));
    assertEquals(ReferenceKind.INDEX_KEYTERM, resolvedKeyterm.kind());
    assertEquals(uuid(22), resolvedKeyterm.targetIndexId());
    assertNull(resolvedKeyterm.targetCardId());

    var external =
        AuthoredReference.external(uuid(30), SOURCE_CARD, uuid(31), "b.Source, xiv", NOW);
    var resolvedExternal = external.resolveSource(uuid(32));
    assertEquals(ReferenceKind.BIBLIOGRAPHIC_SOURCE, resolvedExternal.kind());
    assertEquals(uuid(32), resolvedExternal.targetSourceId());
    assertEquals("b.Source, xiv", resolvedExternal.authoredLiteral());
    assertThrows(IllegalStateException.class, () -> external.resolveCard(uuid(33)));
    assertThrows(IllegalStateException.class, () -> keyterm.resolveSource(uuid(34)));
  }

  @Test
  void pendingAmbiguousAndBrokenStatesNeverEraseOriginalLiteral() {
    var reference =
        AuthoredReference.card(
            uuid(40), SOURCE_CARD, uuid(41), AddressReference.parse("~/1/1a"), NOW);
    var ambiguous = reference.markAmbiguous();
    var broken = reference.resolveCard(uuid(42)).markBroken();
    assertEquals(ResolutionStatus.PENDING, reference.status());
    assertEquals(ResolutionStatus.AMBIGUOUS, ambiguous.status());
    assertEquals(ResolutionStatus.BROKEN, broken.status());
    assertEquals("~/1/1a", ambiguous.authoredLiteral());
    assertEquals("~/1/1a", broken.authoredLiteral());
    assertEquals(uuid(42), broken.targetCardId());
  }

  @Test
  void resolvedReferencesRequireExactlyOneTargetFromTheDeclaredNamespace() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthoredReference(
                uuid(50),
                SOURCE_CARD,
                uuid(51),
                ReferenceKind.STEM,
                "/1",
                "/1",
                null,
                null,
                null,
                ResolutionStatus.RESOLVED,
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthoredReference(
                uuid(52),
                SOURCE_CARD,
                uuid(53),
                ReferenceKind.INDEX_KEYTERM,
                "term",
                "term",
                uuid(54),
                null,
                null,
                ResolutionStatus.RESOLVED,
                NOW));
    assertTrue(AddressReference.parse("/1").resolve(null).isEmpty());
  }

  private static UUID uuid(long tail) {
    return new UUID(0, tail);
  }
}
