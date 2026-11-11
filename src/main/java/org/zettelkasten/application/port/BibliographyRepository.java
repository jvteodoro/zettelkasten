package org.zettelkasten.application.port;

import java.util.Optional;
import java.util.UUID;
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibliographicSource;

/** Persistence port separating imported source metadata from authored research cards. */
public interface BibliographyRepository {
  void insertSource(BibliographicSource source);

  Optional<BibliographicSource> findSource(UUID sourceId);

  void insertBibCard(BibCard card);

  Optional<BibCard> findBibCard(UUID cardId);
}
