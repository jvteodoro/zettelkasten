package org.zettelkasten.application.port;

import java.util.List;

/** Infrastructure seam for parsing a complete BibTeX file without network access. */
public interface BibTeXFileParser {
  List<ParsedBibTeXEntry> parse(String utf8File);
}
