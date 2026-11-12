package org.zettelkasten.application;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.zettelkasten.application.port.UnitOfWork;
import org.zettelkasten.domain.AddressReference;
import org.zettelkasten.domain.AuthoredReference;
import org.zettelkasten.domain.BodyRun;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.CardState;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.domain.KeytermLinkRun;
import org.zettelkasten.domain.ResolutionStatus;

/**
 * Persists typed runs as reference records and resolves targets without changing their literals.
 */
final class AuthoredReferenceService {
  private final Clock clock;

  AuthoredReferenceService(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  void recordRuns(UnitOfWork.Transaction tx, java.util.UUID sourceCardId, List<BodyRun> runs) {
    for (BodyRun run : runs) {
      AuthoredReference reference = reference(sourceCardId, run);
      if (reference != null) tx.references().insert(reference);
    }
  }

  void resolvePending(UnitOfWork.Transaction tx) {
    for (AuthoredReference reference : tx.references().findAll()) {
      if (reference.status() == ResolutionStatus.PENDING) resolveReference(tx, reference);
    }
  }

  private static void resolveReference(UnitOfWork.Transaction tx, AuthoredReference reference) {
    switch (reference.kind()) {
      case STEM, BRANCH, FULL -> {
        var source = tx.mainCards().findById(reference.sourceCardId()).orElse(null);
        var sourceAddress =
            source != null && source.state() == CardState.INSTALLED ? source.address() : null;
        var targetAddress =
            AddressReference.parse(reference.targetLiteral()).resolve(sourceAddress);
        if (targetAddress.isEmpty()) return;
        tx.mainCards()
            .findByAddress(targetAddress.orElseThrow())
            .ifPresent(card -> tx.references().updateResolution(reference.resolveCard(card.id())));
      }
      case INDEX_KEYTERM -> {
        List<UUID> matches =
            tx.indexCards().findKeytermCards().stream()
                .filter(card -> card.keyterm().equals(reference.targetLiteral()))
                .map(card -> card.id())
                .toList();
        if (matches.size() == 1)
          tx.references().updateResolution(reference.resolveIndex(matches.getFirst()));
        else if (matches.size() > 1) tx.references().updateResolution(reference.markAmbiguous());
      }
      case BIBLIOGRAPHIC_SOURCE -> {
        String citationKey = citationKey(reference.targetLiteral());
        if (citationKey == null) return;
        Set<UUID> matches = new LinkedHashSet<>();
        for (var source : tx.bibliography().findSources()) {
          boolean aliasMatches =
              tx.bibliography().findExternalIds(source.id()).stream()
                  .anyMatch(
                      alias ->
                          alias.provider().equals("BIBTEX_FILE")
                              && alias.externalId().equals(citationKey));
          if (aliasMatches) matches.add(source.id());
        }
        if (matches.size() == 1)
          tx.references().updateResolution(reference.resolveSource(matches.iterator().next()));
        else if (matches.size() > 1) tx.references().updateResolution(reference.markAmbiguous());
      }
    }
  }

  private static String citationKey(String literal) {
    if (!literal.startsWith("b.")) return null;
    String sourceAndLocator = literal.substring(2);
    int locatorSeparator = sourceAndLocator.indexOf(',');
    String key =
        (locatorSeparator < 0 ? sourceAndLocator : sourceAndLocator.substring(0, locatorSeparator))
            .trim();
    return key.isEmpty() ? null : key;
  }

  private AuthoredReference reference(java.util.UUID sourceCardId, BodyRun run) {
    if (run instanceof CardLinkRun cardLink)
      return AuthoredReference.card(
          run.id(), sourceCardId, run.id(), cardLink.target(), clock.instant());
    if (run instanceof KeytermLinkRun keytermLink)
      return AuthoredReference.keyterm(
          run.id(), sourceCardId, run.id(), keytermLink.literal(), clock.instant());
    if (run instanceof ExRefRun externalReference)
      return AuthoredReference.external(
          run.id(), sourceCardId, run.id(), externalReference.literal(), clock.instant());
    return null;
  }
}
