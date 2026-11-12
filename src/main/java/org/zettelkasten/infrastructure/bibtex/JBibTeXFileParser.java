package org.zettelkasten.infrastructure.bibtex;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jbibtex.BibTeXDatabase;
import org.jbibtex.BibTeXEntry;
import org.jbibtex.BibTeXParser;
import org.jbibtex.Key;
import org.jbibtex.Value;
import org.zettelkasten.application.BibTeXParseException;
import org.zettelkasten.application.port.BibTeXFileParser;
import org.zettelkasten.application.port.ParsedBibTeXEntry;

/** JBibTeX adapter. The full input stays attached to each parsed record as archival raw data. */
public final class JBibTeXFileParser implements BibTeXFileParser {
  private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
  private static final int MAX_BRACE_DEPTH = 256;

  @Override
  public List<ParsedBibTeXEntry> parse(String utf8File) {
    try {
      enforceInputLimits(utf8File);
      BibTeXDatabase database = new BibTeXParser().parse(new StringReader(utf8File));
      List<ParsedBibTeXEntry> entries = new ArrayList<>();
      for (var object : database.getObjects()) {
        if (!(object instanceof BibTeXEntry entry)) continue;
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<Key, Value> field : entry.getFields().entrySet()) {
          fields.put(field.getKey().getValue(), field.getValue().toUserString());
        }
        Set<Key> seenCrossReferences = new HashSet<>();
        seenCrossReferences.add(entry.getKey());
        for (BibTeXEntry parent = entry.getCrossReference();
            parent != null;
            parent = parent.getCrossReference()) {
          if (!seenCrossReferences.add(parent.getKey())) {
            throw new BibTeXParseException(
                "Cyclic BibTeX cross-reference involving " + entry.getKey().getValue() + ".", null);
          }
          for (Map.Entry<Key, Value> field : parent.getFields().entrySet()) {
            fields.putIfAbsent(field.getKey().getValue(), field.getValue().toUserString());
          }
        }
        entries.add(
            new ParsedBibTeXEntry(
                entry.getKey().getValue(), entry.getType().getValue(), fields, utf8File));
      }
      if (entries.isEmpty()) {
        throw new BibTeXParseException("BibTeX file contains no entries.", null);
      }
      return List.copyOf(entries);
    } catch (Exception exception) {
      throw new BibTeXParseException(
          "BibTeX file could not be parsed: " + exception.getMessage(), exception);
    }
  }

  private static void enforceInputLimits(String input) {
    if (input.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
      throw new BibTeXParseException("BibTeX file exceeds the 16 MiB import limit.", null);
    }
    int depth = 0;
    boolean escaped = false;
    boolean comment = false;
    for (int offset = 0; offset < input.length(); offset++) {
      char character = input.charAt(offset);
      if (comment) {
        if (character == '\n' || character == '\r') comment = false;
        continue;
      }
      if (escaped) {
        escaped = false;
        continue;
      }
      if (character == '\\') {
        escaped = true;
        continue;
      }
      if (character == '%' && depth == 0) {
        comment = true;
      } else if (character == '{') {
        depth++;
        if (depth > MAX_BRACE_DEPTH) {
          throw new BibTeXParseException("BibTeX nesting exceeds the 256 brace depth limit.", null);
        }
      } else if (character == '}' && depth > 0) {
        depth--;
      }
    }
  }
}
