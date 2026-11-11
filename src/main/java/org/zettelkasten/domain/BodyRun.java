package org.zettelkasten.domain;

import java.util.UUID;

/**
 * One authored, ordered span in a card body. Its kind is explicit; text is never scanned for links.
 */
public sealed interface BodyRun permits TextRun, CardLinkRun, KeytermLinkRun, ExRefRun {
  UUID id();

  /** The exact literal shown to the user and preserved in the archive. */
  String literal();
}
