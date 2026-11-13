package org.zettelkasten.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared identity and creation-time contract for the two deliberately authored Index card forms.
 */
public sealed interface IndexCard permits ListIndexCard, KeytermIndexCard {
  UUID id();

  Instant createdAt();
}
