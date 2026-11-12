package org.zettelkasten.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibNote;
import org.zettelkasten.domain.BibliographicSource;
import org.zettelkasten.domain.SourceExternalId;

/** Persistence port separating imported source metadata from authored research cards. */
public interface BibliographyRepository {
  void insertSource(BibliographicSource source);

  Optional<BibliographicSource> findSource(UUID sourceId);

  void insertBibCard(BibCard card);

  Optional<BibCard> findBibCard(UUID cardId);

  List<BibliographicSource> findSources();

  List<BibliographicSource> findSourcesByDoi(String doi);

  Optional<UUID> findSourceIdByExternalId(String provider, String scope, String externalId);

  void insertSourceExternalId(SourceExternalId externalId);

  void insertBibNote(BibNote note);

  List<BibNote> findBibNotes(UUID bibCardId);

  void insertImportBatch(
      UUID id,
      String namespace,
      String sourceName,
      String contentSha256,
      String status,
      Instant importedAt);

  void insertImportEntry(
      UUID id,
      UUID batchId,
      UUID sourceId,
      String externalKey,
      String rawRecord,
      String outcome,
      String diagnostic);
}
