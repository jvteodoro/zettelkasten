package org.zettelkasten.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Responsive plain-text renderer that escapes terminal control characters in card content. */
public final class TerminalRenderer {
  private static final int MIN_COLUMNS = 40;
  private static final int MIN_ROWS = 12;

  public void render(Screen screen, TuiController controller) throws IOException {
    TerminalSize size = screen.doResizeIfNecessary();
    if (size == null) size = screen.getTerminalSize();
    screen.clear();
    TextGraphics graphics = screen.newTextGraphics();
    if (size.getColumns() < MIN_COLUMNS || size.getRows() < MIN_ROWS) {
      graphics.putString(0, 0, "Terminal too small. Please use at least 40x12.");
      screen.refresh();
      return;
    }
    int width = size.getColumns() - 2;
    graphics.setForegroundColor(TextColor.ANSI.CYAN);
    graphics.putString(1, 0, "ZETTELKASTEN — analog-first local slip-box", SGR.BOLD);
    graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
    graphics.putString(1, 1, screenTitle(controller));
    graphics.drawLine(0, 2, size.getColumns() - 1, 2, '─');
    int footer = footerPosition(size);
    List<String> body = wrap(controller.viewLines(), width, footer - 3);
    for (int row = 0; row < body.size(); row++) {
      String line = body.get(row);
      int y = row + 3;
      if (controller.screen() == TuiController.Screen.MAIN && line.startsWith("Address ")) {
        graphics.setForegroundColor(TextColor.ANSI.YELLOW);
        graphics.putString(1, y, fit(sanitize(line), width), SGR.BOLD, SGR.UNDERLINE);
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
      } else {
        graphics.putString(1, y, fit(sanitize(line), width));
      }
    }
    if (controller.screen() == TuiController.Screen.EDITOR
        && controller.editor() != null
        && (controller.editor().mode() == EditorBuffer.Mode.TEXT
            || controller.editor().mode() == EditorBuffer.Mode.NORMAL)) {
      int headerRows =
          wrap(List.of(controller.viewLines().getFirst()), width, size.getRows()).size();
      int[] cursor =
          cursorLocation(
              controller.editor().renderedText(), controller.editor().cursorUtf16Offset(), width);
      int cursorX = Math.min(size.getColumns() - 1, 1 + cursor[1]);
      int cursorY = Math.min(footer - 1, 3 + headerRows + cursor[0]);
      screen.setCursorPosition(new TerminalPosition(cursorX, cursorY));
    }
    graphics.drawLine(0, footer, size.getColumns() - 1, footer, '─');
    graphics.setForegroundColor(TextColor.ANSI.CYAN);
    graphics.putString(1, footer + 1, fit(sanitize(navigationHint(controller)), width), SGR.BOLD);
    graphics.setForegroundColor(TextColor.ANSI.YELLOW);
    String prompt = footerPrompt(controller);
    graphics.putString(1, footer + 2, fit(sanitize(prompt), width));
    graphics.setForegroundColor(TextColor.ANSI.GREEN);
    String footerStatus = controller.status();
    if (controller.screen() == TuiController.Screen.EDITOR
        && controller.editor() != null
        && !controller.editor().validationMessage().isEmpty()) {
      footerStatus = controller.editor().validationMessage();
    }
    graphics.putString(1, footer + 3, fit(sanitize(footerStatus), width));
    graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
    screen.refresh();
  }

  private static String screenTitle(TuiController controller) {
    if (controller.screen() == TuiController.Screen.EDITOR) return "Card editor";
    return switch (controller.screen()) {
      case HOME -> "Home";
      case DESK -> "Desk";
      case MAIN -> "Main";
      case INDEX, INDEX_LIST_CARD, KEYTERM_CARDS, KEYTERM_CARD, INDEX_INPUT -> "Index";
      case BIB, BIB_SOURCE, BIB_CARD, BIB_INPUT, BIB_IMPORT_PREVIEW -> "Bibliography";
      case HELP -> "Keyboard help";
      default -> "Input";
    };
  }

  private static String navigationHint(TuiController controller) {
    if (controller.screen() == TuiController.Screen.EDITOR && controller.editor() != null) {
      return switch (controller.editor().mode()) {
        case TEXT -> "INSERT — type freely   Esc NORMAL";
        case NORMAL ->
            "NORMAL — H/J/K/L Move   I Insert   :w Save   :q Exit   :wq Save+Exit   Esc/B Home";
        case COMMAND -> "COMMAND — type w, q, or wq   Enter Run   Esc Cancel";
        case CARD_REFERENCE, INDEX_LITERAL, EXTERNAL_REFERENCE ->
            "LITERAL PROMPT — Enter confirm   Esc cancel";
      };
    }
    return "J/K Select   H/L Navigate or back   L/Enter Open   B Home   ? Help   Q Quit";
  }

  private static String footerPrompt(TuiController controller) {
    if (controller.screen() == TuiController.Screen.EDITOR && controller.editor() != null) {
      EditorBuffer editor = controller.editor();
      if (editor.mode() == EditorBuffer.Mode.COMMAND
          || editor.mode() == EditorBuffer.Mode.CARD_REFERENCE
          || editor.mode() == EditorBuffer.Mode.INDEX_LITERAL
          || editor.mode() == EditorBuffer.Mode.EXTERNAL_REFERENCE) {
        return editor.promptLabel() + editor.promptText() + "    Enter confirm   Esc cancel";
      }
    }
    return "";
  }

  public static List<String> wrap(List<String> lines, int width, int maxRows) {
    if (width < 1 || maxRows < 0) throw new IllegalArgumentException("invalid terminal layout");
    List<String> result = new ArrayList<>();
    for (String line : lines) {
      String safe = sanitize(line);
      StringBuilder current = new StringBuilder();
      int columns = 0;
      for (int offset = 0; offset < safe.length(); ) {
        int codePoint = safe.codePointAt(offset);
        offset += Character.charCount(codePoint);
        if (codePoint == '\n') {
          result.add(current.toString());
          current.setLength(0);
          columns = 0;
          continue;
        }
        String glyph = new String(Character.toChars(codePoint));
        int glyphWidth = TerminalTextUtils.getColumnWidth(glyph);
        if (columns + glyphWidth > width && current.length() > 0) {
          result.add(current.toString());
          current.setLength(0);
          columns = 0;
        }
        current.append(glyph);
        columns += glyphWidth;
      }
      result.add(current.toString());
      if (result.size() >= maxRows) break;
    }
    if (result.size() > maxRows) return List.copyOf(result.subList(0, maxRows));
    return List.copyOf(result);
  }

  static String sanitize(String value) {
    if (value == null || value.isEmpty()) return "";
    StringBuilder safe = new StringBuilder(value.length());
    value
        .codePoints()
        .forEach(
            codePoint -> {
              if (codePoint == '\n') safe.appendCodePoint(codePoint);
              else if (codePoint == '\t') safe.append("    ");
              else if (Character.isISOControl(codePoint)) safe.append('\uFFFD');
              else safe.appendCodePoint(codePoint);
            });
    return safe.toString();
  }

  private static int[] cursorLocation(String text, int utf16Offset, int width) {
    String prefix = text.substring(0, Math.max(0, Math.min(text.length(), utf16Offset)));
    int row = 0;
    int column = 0;
    for (int offset = 0; offset < prefix.length(); ) {
      int codePoint = prefix.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (codePoint == '\n') {
        row++;
        column = 0;
        continue;
      }
      int glyphWidth = TerminalTextUtils.getColumnWidth(new String(Character.toChars(codePoint)));
      if (column + glyphWidth > width) {
        row++;
        column = 0;
      }
      column += glyphWidth;
      if (column == width) {
        row++;
        column = 0;
      }
    }
    return new int[] {row, column};
  }

  private static int footerPosition(TerminalSize size) {
    return size.getRows() - 4;
  }

  private static String fit(String value, int width) {
    if (value == null) return "";
    return TerminalTextUtils.fitString(value, width);
  }
}
