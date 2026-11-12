package org.zettelkasten.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.zettelkasten.application.port.UnitOfWork;
import org.zettelkasten.domain.AddressReference;
import org.zettelkasten.domain.AuthoredReference;
import org.zettelkasten.domain.BodyRun;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.CardState;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.domain.KeytermLinkRun;
import org.zettelkasten.domain.ReferenceKind;
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
      if (reference.status() != ResolutionStatus.PENDING || !isCardReference(reference.kind()))
        continue;
      var source = tx.mainCards().findById(reference.sourceCardId()).orElse(null);
      var sourceAddress =
          source != null && source.state() == CardState.INSTALLED ? source.address() : null;
      var targetAddress = AddressReference.parse(reference.targetLiteral()).resolve(sourceAddress);
      if (targetAddress.isEmpty()) continue;
      var target = tx.mainCards().findByAddress(targetAddress.orElseThrow());
      target.ifPresent(card -> tx.references().updateResolution(reference.resolveCard(card.id())));
    }
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

  private static boolean isCardReference(ReferenceKind kind) {
    return kind == ReferenceKind.STEM || kind == ReferenceKind.BRANCH || kind == ReferenceKind.FULL;
  }
}
