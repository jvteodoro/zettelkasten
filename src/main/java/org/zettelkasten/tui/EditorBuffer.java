package org.zettelkasten.tui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.zettelkasten.domain.BodyRun;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.domain.KeytermLinkRun;
import org.zettelkasten.domain.TextRun;

/** Structured editor state machine. Printable keys remain literal unless a Ctrl chord is used. */
public final class EditorBuffer {
  public enum Mode {
    TEXT,
    CARD_REFERENCE,
    INDEX_LITERAL,
    EXTERNAL_REFERENCE
  }

  public enum Action {
    NONE,
    SAVE,
    EXIT
  }

  private final List<BodyRun> runs;
  private final Supplier<UUID> ids;
  private final int softLimit;
  private int runIndex;
  private int codePointOffset;
  private Mode mode = Mode.TEXT;
  private final StringBuilder prompt = new StringBuilder();
  private String validationMessage = "";
  private boolean dirty;

  public EditorBuffer(List<BodyRun> initialRuns, Supplier<UUID> ids) {
    this(initialRuns, ids, 825);
  }

  public EditorBuffer(List<BodyRun> initialRuns, Supplier<UUID> ids, int softLimit) {
    this.runs = new ArrayList<>(Objects.requireNonNull(initialRuns, "initialRuns"));
    this.ids = Objects.requireNonNull(ids, "ids");
    if (softLimit < 26) throw new IllegalArgumentException("soft limit must be at least 26");
    this.softLimit = softLimit;
    runIndex = runs.size();
    if (!runs.isEmpty() && runs.getLast() instanceof TextRun text) {
      runIndex--;
      codePointOffset = codePoints(text.literal());
    }
  }

  public Action handle(KeyStroke key) {
    Objects.requireNonNull(key, "key");
    if (key.getKeyType() == KeyType.Escape) {
      if (mode != Mode.TEXT) {
        mode = Mode.TEXT;
        prompt.setLength(0);
        validationMessage = "";
        return Action.NONE;
      }
      return Action.EXIT;
    }
    if (mode != Mode.TEXT) return handlePrompt(key);
    if (key.getKeyType() == KeyType.Character && key.isCtrlDown()) {
      char chord = Character.toLowerCase(key.getCharacter());
      if (chord == 's') return Action.SAVE;
      if (chord == 'l') return openPrompt(Mode.CARD_REFERENCE);
      if (chord == 'k') return openPrompt(Mode.INDEX_LITERAL);
      if (chord == 'e') return openPrompt(Mode.EXTERNAL_REFERENCE);
      return Action.NONE;
    }
    if (key.getKeyType() == KeyType.Character && !key.isCtrlDown() && !key.isAltDown()) {
      insertCharacter(key.getCharacter());
    } else if (key.getKeyType() == KeyType.Enter) {
      insertCharacter('\n');
    } else if (key.getKeyType() == KeyType.Backspace) {
      deleteBeforeCursor();
    } else if (key.getKeyType() == KeyType.ArrowLeft) {
      moveLeft();
    } else if (key.getKeyType() == KeyType.ArrowRight) {
      moveRight();
    }
    return Action.NONE;
  }

  public List<BodyRun> runs() {
    return List.copyOf(runs);
  }

  public Mode mode() {
    return mode;
  }

  public String promptText() {
    return prompt.toString();
  }

  public String promptLabel() {
    return switch (mode) {
      case TEXT -> "";
      case CARD_REFERENCE -> "Card address literal (full, /stem, or ~/branch): ";
      case INDEX_LITERAL -> "Index keyterm literal: ";
      case EXTERNAL_REFERENCE -> "External reference literal: ";
    };
  }

  public String validationMessage() {
    return validationMessage;
  }

  public boolean dirty() {
    return dirty;
  }

  public int codePointCount() {
    return runs.stream().mapToInt(run -> codePoints(run.literal())).sum();
  }

  public boolean softLimitWarning() {
    return codePointCount() >= softLimit - 25;
  }

  public boolean softLimitReached() {
    return codePointCount() >= softLimit;
  }

  public int softLimit() {
    return softLimit;
  }

  public String renderedText() {
    StringBuilder result = new StringBuilder();
    for (BodyRun run : runs) result.append(run.literal());
    return result.toString();
  }

  public int cursorUtf16Offset() {
    int offset = 0;
    for (int index = 0; index < Math.min(runIndex, runs.size()); index++)
      offset += runs.get(index).literal().length();
    if (runIndex < runs.size() && runs.get(runIndex) instanceof TextRun text)
      offset += utf16Offset(text.literal(), codePointOffset);
    return offset;
  }

  private Action openPrompt(Mode requestedMode) {
    mode = requestedMode;
    prompt.setLength(0);
    validationMessage = "";
    return Action.NONE;
  }

  private Action handlePrompt(KeyStroke key) {
    if (key.getKeyType() == KeyType.Enter) {
      try {
        if (prompt.isEmpty()) throw new IllegalArgumentException("Literal cannot be empty");
        BodyRun run =
            switch (mode) {
              case CARD_REFERENCE -> new CardLinkRun(ids.get(), prompt.toString());
              case INDEX_LITERAL -> new KeytermLinkRun(ids.get(), prompt.toString());
              case EXTERNAL_REFERENCE -> new ExRefRun(ids.get(), prompt.toString());
              case TEXT -> throw new IllegalStateException("Not a prompt mode");
            };
        insertRunAtCursor(run);
        mode = Mode.TEXT;
        prompt.setLength(0);
        validationMessage = "";
      } catch (IllegalArgumentException exception) {
        validationMessage = exception.getMessage();
      }
    } else if (key.getKeyType() == KeyType.Backspace) {
      if (prompt.length() > 0) {
        int codePoint = prompt.codePointBefore(prompt.length());
        prompt.delete(prompt.length() - Character.charCount(codePoint), prompt.length());
      }
      validationMessage = "";
    } else if (key.getKeyType() == KeyType.Character
        && !key.isCtrlDown()
        && !key.isAltDown()
        && !Character.isISOControl(key.getCharacter())) {
      prompt.append(key.getCharacter());
      validationMessage = "";
    }
    return Action.NONE;
  }

  private void insertCharacter(char character) {
    if (Character.isISOControl(character) && character != '\n' && character != '\t') return;
    if (runIndex < runs.size() && runs.get(runIndex) instanceof TextRun text) {
      String value = text.literal();
      int offset = utf16Offset(value, codePointOffset);
      boolean completingSurrogate =
          Character.isLowSurrogate(character)
              && offset > 0
              && Character.isHighSurrogate(value.charAt(offset - 1));
      runs.set(
          runIndex,
          new TextRun(text.id(), value.substring(0, offset) + character + value.substring(offset)));
      if (!completingSurrogate) codePointOffset++;
    } else if (runIndex > 0 && runs.get(runIndex - 1) instanceof TextRun text) {
      int index = runIndex - 1;
      boolean completingSurrogate =
          Character.isLowSurrogate(character)
              && !text.literal().isEmpty()
              && Character.isHighSurrogate(text.literal().charAt(text.literal().length() - 1));
      runs.set(index, new TextRun(text.id(), text.literal() + character));
      runIndex = index;
      codePointOffset = codePoints(text.literal()) + (completingSurrogate ? 0 : 1);
    } else {
      runs.add(runIndex, new TextRun(ids.get(), String.valueOf(character)));
      codePointOffset = 1;
    }
    dirty = true;
  }

  private void insertRunAtCursor(BodyRun insertion) {
    if (runIndex < runs.size() && runs.get(runIndex) instanceof TextRun text) {
      int offset = utf16Offset(text.literal(), codePointOffset);
      String prefix = text.literal().substring(0, offset);
      String suffix = text.literal().substring(offset);
      if (prefix.isEmpty()) {
        runs.remove(runIndex);
        runs.add(runIndex, insertion);
        if (!suffix.isEmpty()) runs.add(runIndex + 1, new TextRun(text.id(), suffix));
        runIndex++;
        codePointOffset = 0;
      } else {
        runs.set(runIndex, new TextRun(text.id(), prefix));
        runs.add(runIndex + 1, insertion);
        if (!suffix.isEmpty()) runs.add(runIndex + 2, new TextRun(ids.get(), suffix));
        runIndex += 2;
        codePointOffset = 0;
      }
    } else {
      runs.add(runIndex, insertion);
      runIndex++;
      codePointOffset = 0;
    }
    dirty = true;
  }

  private void deleteBeforeCursor() {
    if (runIndex < runs.size()
        && runs.get(runIndex) instanceof TextRun text
        && codePointOffset > 0) {
      removeCodePoint(text, codePointOffset - 1);
      return;
    }
    int previous = runIndex - 1;
    if (previous < 0) return;
    BodyRun run = runs.get(previous);
    if (run instanceof TextRun text) {
      runIndex = previous;
      codePointOffset = codePoints(text.literal());
      removeCodePoint(text, codePointOffset - 1);
    } else {
      runs.remove(previous);
      runIndex = previous;
      codePointOffset = 0;
      dirty = true;
    }
  }

  private void removeCodePoint(TextRun text, int offset) {
    if (offset < 0) return;
    int start = utf16Offset(text.literal(), offset);
    int end = utf16Offset(text.literal(), offset + 1);
    String updated = text.literal().substring(0, start) + text.literal().substring(end);
    if (updated.isEmpty()) {
      runs.remove(runIndex);
      codePointOffset = 0;
    } else {
      runs.set(runIndex, new TextRun(text.id(), updated));
      codePointOffset = offset;
    }
    dirty = true;
  }

  private void moveLeft() {
    if (runIndex < runs.size() && runs.get(runIndex) instanceof TextRun && codePointOffset > 0) {
      codePointOffset--;
    } else if (runIndex > 0 && runs.get(runIndex - 1) instanceof TextRun previous) {
      runIndex--;
      codePointOffset = codePoints(previous.literal());
    } else if (runIndex > 0) {
      runIndex--;
      codePointOffset = 0;
    }
  }

  private void moveRight() {
    if (runIndex < runs.size() && runs.get(runIndex) instanceof TextRun text) {
      if (codePointOffset < codePoints(text.literal())) codePointOffset++;
      else {
        runIndex++;
        codePointOffset = 0;
      }
    } else if (runIndex < runs.size()) {
      runIndex++;
      codePointOffset = 0;
    }
  }

  private static int utf16Offset(String value, int codePointOffset) {
    return value.offsetByCodePoints(0, codePointOffset);
  }

  private static int codePoints(String value) {
    return value.codePointCount(0, value.length());
  }
}
