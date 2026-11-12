package org.zettelkasten.infrastructure.bibtex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.zettelkasten.application.BibTeXParseException;

class JBibTeXFileParserTest {
  private final JBibTeXFileParser parser = new JBibTeXFileParser();

  @Test
  void parsesMacrosCrossReferencesUnicodeAndUnknownFieldsWithoutLosingRawFile() {
    String bibtex =
        """
        @string{journalname = {Journal of Zettelkasten}}
        @book{parent,
          publisher = {Über Press},
          year = {2020}
        }
        @article{child,
          author = {García, José},
          title = {A {Nested} Argument},
          journal = journalname,
          crossref = {parent},
          customfield = {保留 me}
        }
        @preamble{""}
        """;

    var entries = parser.parse(bibtex);
    assertEquals(2, entries.size());
    var child = entries.get(1);
    assertEquals("child", child.citationKey());
    assertEquals("article", child.entryType());
    assertEquals("Journal of Zettelkasten", child.fields().get("journal"));
    assertEquals("parent", child.fields().get("crossref"));
    assertEquals("Über Press", child.fields().get("publisher"));
    assertEquals("保留 me", child.fields().get("customfield"));
    assertTrue(child.rawFile().contains("@preamble"));
  }

  @Test
  void malformedInputProducesAnActionableParseError() {
    var error =
        assertThrows(BibTeXParseException.class, () -> parser.parse("@book{broken, title={"));
    assertTrue(error.getMessage().contains("could not be parsed"));
  }

  @Test
  void rejectsExcessiveBraceNestingBeforeInvokingTheParser() {
    String nested = "{".repeat(257) + "}".repeat(257);
    var error = assertThrows(BibTeXParseException.class, () -> parser.parse(nested));
    assertTrue(error.getMessage().contains("256 brace depth limit"));
  }

  @Test
  void rejectsEmptyInputAsAnImportFile() {
    var error = assertThrows(BibTeXParseException.class, () -> parser.parse("  % no records\n"));
    assertTrue(error.getMessage().contains("contains no entries"));
  }
}
