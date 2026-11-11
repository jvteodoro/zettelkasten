package org.zettelkasten.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.domain.KeytermLinkRun;

class EditorBufferTest {
  @Test
  void lowercaseLIsTextAndCtrlLPromptsForAnAuthoredLiteralWithoutSuggestions() {
    var editor = editor();
    assertEquals(825, editor.softLimit());
    editor.handle(character('l'));
    assertEquals("l", editor.renderedText());
    assertEquals(EditorBuffer.Action.NONE, editor.handle(ctrl('l')));
    assertEquals(EditorBuffer.Mode.CARD_REFERENCE, editor.mode());
    assertEquals("", editor.promptText());
    type(editor, "~/1/1a");
    assertEquals(EditorBuffer.Action.NONE, editor.handle(key(KeyType.Enter)));
    assertEquals("l~/1/1a", editor.renderedText());
    assertTrue(editor.runs().get(1) instanceof CardLinkRun);
    assertEquals("~/1/1a", editor.runs().get(1).literal());
  }

  @Test
  void modalShortcutsInsertIndexAndExternalReferenceRunsAndEscapeCancelsCleanly() {
    var editor = editor();
    editor.handle(ctrl('k'));
    type(editor, "Überblick");
    editor.handle(key(KeyType.Enter));
    editor.handle(ctrl('e'));
    type(editor, "b.Source, xiv");
    editor.handle(key(KeyType.Enter));
    editor.handle(ctrl('l'));
    type(editor, "bad");
    assertEquals(EditorBuffer.Action.NONE, editor.handle(key(KeyType.Escape)));

    assertEquals(2, editor.runs().size());
    assertTrue(editor.runs().get(0) instanceof KeytermLinkRun);
    assertTrue(editor.runs().get(1) instanceof ExRefRun);
    assertEquals("Überblickb.Source, xiv", editor.renderedText());
    assertEquals(EditorBuffer.Mode.TEXT, editor.mode());
  }

  @Test
  void emptyLiteralPromptCannotCreateAnEmptyTypedRun() {
    var editor = editor();
    editor.handle(ctrl('k'));
    editor.handle(key(KeyType.Enter));
    assertEquals(EditorBuffer.Mode.INDEX_LITERAL, editor.mode());
    assertTrue(editor.runs().isEmpty());
    assertEquals("Literal cannot be empty", editor.validationMessage());
  }

  @Test
  void unicodeInsertionBackspaceAndSoftWarningNeverTruncate() {
    var editor = editor();
    type(editor, "漢字e\u0301🙂");
    assertEquals(5, editor.codePointCount());
    editor.handle(key(KeyType.Backspace));
    assertEquals("漢字e\u0301", editor.renderedText());
    assertEquals(4, editor.codePointCount());
    editor.handle(key(KeyType.Backspace));
    assertEquals("漢字e", editor.renderedText());

    for (int index = editor.codePointCount(); index < 800; index++) editor.handle(character('x'));
    assertTrue(editor.softLimitWarning());
    for (int index = editor.codePointCount(); index < 900; index++) editor.handle(character('y'));
    assertEquals(900, editor.codePointCount());
    assertEquals(900, editor.renderedText().codePointCount(0, editor.renderedText().length()));
  }

  @Test
  void saveIsAnExplicitActionAndCursorInsertionPreservesTypedRuns() {
    var editor = editor();
    type(editor, "ab");
    editor.handle(ctrl('l'));
    type(editor, "2/1");
    editor.handle(key(KeyType.Enter));
    editor.handle(key(KeyType.ArrowLeft));
    editor.handle(character('l'));
    assertEquals("abl2/1", editor.renderedText());
    assertTrue(editor.handle(ctrl('s')) == EditorBuffer.Action.SAVE);
    assertFalse(editor.runs().isEmpty());
  }

  private static EditorBuffer editor() {
    AtomicLong sequence = new AtomicLong();
    return new EditorBuffer(List.of(), () -> new UUID(0, sequence.incrementAndGet()));
  }

  private static KeyStroke character(char value) {
    return new KeyStroke(value, false, false);
  }

  private static KeyStroke ctrl(char value) {
    return new KeyStroke(value, true, false);
  }

  private static KeyStroke key(KeyType keyType) {
    return new KeyStroke(keyType);
  }

  private static void type(EditorBuffer editor, String value) {
    value.chars().forEach(unit -> editor.handle(character((char) unit)));
  }
}
