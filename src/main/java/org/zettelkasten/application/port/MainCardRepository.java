package org.zettelkasten.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.zettelkasten.domain.CardAnnotation;
import org.zettelkasten.domain.MainCard;
import org.zettelkasten.domain.NumericAlphaAddress;
import org.zettelkasten.domain.PlacementSnapshot;

/**
 * Persistence port for Desk/Main aggregates. It has no delete or installed-body rewrite operation.
 */
public interface MainCardRepository {
  Optional<MainCard> findById(UUID id);

  Optional<MainCard> findByAddress(NumericAlphaAddress address);

  List<MainCard> findDrafts();

  List<MainCard> findInstalledInAddressOrder();

  void insertDraft(MainCard draft);

  void updateDraft(MainCard draft, long expectedRevision);

  void discardDraft(UUID cardId, long expectedRevision, java.time.Instant discardedAt);

  void install(MainCard installed, PlacementSnapshot placement, long expectedDraftRevision);

  void appendAnnotation(UUID cardId, CardAnnotation annotation);
}
