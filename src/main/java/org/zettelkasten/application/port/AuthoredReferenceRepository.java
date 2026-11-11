package org.zettelkasten.application.port;

import java.util.List;
import java.util.UUID;
import org.zettelkasten.domain.AuthoredReference;

/** Persistence port for intentional reference occurrences, including unresolved literals. */
public interface AuthoredReferenceRepository {
  void insert(AuthoredReference reference);

  List<AuthoredReference> findBySourceCard(UUID sourceCardId);
}
