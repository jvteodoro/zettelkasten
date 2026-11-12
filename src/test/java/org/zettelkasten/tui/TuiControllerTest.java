package org.zettelkasten.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zettelkasten.application.BibliographyService;
import org.zettelkasten.application.IndexService;
import org.zettelkasten.application.MainCardService;
import org.zettelkasten.domain.BibNote;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.domain.MainCard;
import org.zettelkasten.infrastructure.bibtex.JBibTeXFileParser;
import org.zettelkasten.infrastructure.sqlite.SqliteDatabase;

class TuiControllerTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @TempDir java.nio.file.Path temp;

  @Test
  void deskEditorSavesTypedLiteralLinkAndManualInstallWithoutFollowingIt() {
    var database = database();
    var service = service(database);
    var controller = new TuiController(service, sequence(300));
    controller.handle(character('l'));
    assertEquals(TuiController.Screen.DESK, controller.screen());
    controller.handle(character('n'));
    assertEquals(TuiController.Screen.EDITOR, controller.screen());
    controller.handle(character('l'));
    controller.handle(key(KeyType.Escape));
    controller.handle(character('r'));
    type(controller, "3118/2a");
    controller.handle(key(KeyType.Enter));
    assertEquals(1, eventCount(database));
    type(controller, ":w");
    controller.handle(key(KeyType.Enter));
    assertEquals(3, eventCount(database));
    controller.handle(key(KeyType.Escape));
    assertEquals(TuiController.Screen.DESK, controller.screen());

    var draft = service.getDrafts().getFirst();
    assertEquals(
        "l3118/2a",
        draft.authoredBody().stream().map(run -> run.literal()).reduce("", String::concat));
    assertTrue(draft.authoredBody().stream().anyMatch(CardLinkRun.class::isInstance));
    assertFalse(controller.viewLines().toString().contains("Backlinks"));

    controller.handle(character('f'));
    type(controller, "3118/1");
    controller.handle(key(KeyType.Enter));
    assertEquals(TuiController.Screen.DESK, controller.screen());
    assertEquals("3118/1", service.getInstalledInAddressOrder().getFirst().addressLiteral());
    assertTrue(controller.status().contains("filed"));

    controller.handle(key(KeyType.Escape));
    controller.handle(character('j'));
    controller.handle(character('l'));
    assertEquals(TuiController.Screen.MAIN, controller.screen());
    assertEquals(
        "3118/1",
        controller.viewLines().stream()
            .filter(line -> line.startsWith("Address "))
            .findFirst()
            .orElseThrow()
            .substring(8));
  }

  @Test
  void vimNavigationMovesBoxesAndListsWhileEditorKeepsLettersLiteral() {
    var database = database();
    var service = service(database);
    MainCard first =
        service.createDraft(
            List.of(new org.zettelkasten.domain.TextRun(new UUID(0, 801), "first draft")));
    MainCard second =
        service.createDraft(
            List.of(new org.zettelkasten.domain.TextRun(new UUID(0, 802), "second draft")));
    MainCard one = service.createDraft(List.of());
    MainCard two = service.createDraft(List.of());
    service.installMainCard(one.id(), 0, "3118/1");
    service.installMainCard(two.id(), 0, "3118/2");

    var controller = new TuiController(service, sequence(803));
    controller.handle(character('j'));
    assertTrue(controller.viewLines().get(1).startsWith("> Main"));
    controller.handle(character('l'));
    assertEquals(TuiController.Screen.MAIN, controller.screen());
    assertTrue(controller.viewLines().contains("Address 3118/1"));
    controller.handle(character('j'));
    assertTrue(controller.viewLines().contains("Address 3118/2"));
    controller.handle(character('k'));
    assertTrue(controller.viewLines().contains("Address 3118/1"));
    controller.handle(character('h'));
    assertEquals(TuiController.Screen.HOME, controller.screen());

    controller.handle(character('k'));
    controller.handle(character('l'));
    assertEquals(TuiController.Screen.DESK, controller.screen());
    controller.handle(character('j'));
    controller.handle(character('l'));
    assertEquals(second.id(), service.getDrafts().get(1).id());
    assertEquals("second draft", controller.editor().renderedText());
    controller.handle(character('j'));
    assertEquals("second draftj", controller.editor().renderedText());
    assertEquals(first.id(), service.getDrafts().getFirst().id());
  }

  @Test
  void unsavedExitRequiresAnExplicitChoiceAndCtrlCDoesNotDiscard() {
    var database = database();
    var service = service(database);
    var controller = new TuiController(service, sequence(400));
    controller.handle(character('l'));
    controller.handle(character('n'));
    controller.handle(character('x'));
    controller.handle(new KeyStroke((char) 3, true, false));
    assertTrue(controller.status().contains("no card data was discarded"));
    controller.handle(key(KeyType.Escape));
    assertEquals(EditorBuffer.Mode.NORMAL, controller.editor().mode());
    controller.handle(key(KeyType.Escape));
    assertEquals(TuiController.Screen.CONFIRM_DISCARD, controller.screen());
    controller.handle(character('n'));
    assertEquals(TuiController.Screen.DESK, controller.screen());
    assertEquals(1, service.getDrafts().size());
    assertTrue(service.getDrafts().getFirst().authoredBody().isEmpty());
  }

  @Test
  void savedDeskDraftDiscardRequiresConfirmationAndRetainsTombstone() {
    var database = database();
    var service = service(database);
    var saved =
        service.createDraft(
            List.of(new org.zettelkasten.domain.TextRun(new UUID(0, 777), "keep audit")));
    var controller = new TuiController(service, sequence(780));
    controller.handle(character('l'));
    controller.handle(character('x'));
    assertEquals(TuiController.Screen.CONFIRM_SAVED_DRAFT_DISCARD, controller.screen());
    controller.handle(character('n'));
    assertEquals(1, service.getDrafts().size());
    controller.handle(character('x'));
    controller.handle(character('y'));
    assertEquals(TuiController.Screen.DESK, controller.screen());
    assertTrue(service.getDrafts().isEmpty());
    var retained =
        database.unitOfWork().transact(tx -> tx.mainCards().findById(saved.id()).orElseThrow());
    assertEquals(org.zettelkasten.domain.CardState.DISCARDED, retained.state());
    assertEquals("keep audit", retained.authoredBody().getFirst().literal());
  }

  @Test
  void responsiveWrappingHandlesReferenceSizesUnicodeAndUntrustedControlCharacters() {
    List<String> source = List.of("Português 漢字 e\u0301", "unsafe\u001b[31m text");
    for (var size : List.of(new int[] {78, 18}, new int[] {118, 29})) {
      List<String> wrapped = TerminalRenderer.wrap(source, size[0], size[1]);
      assertTrue(wrapped.size() <= size[1]);
      assertTrue(
          wrapped.stream().allMatch(line -> TerminalTextUtils.getColumnWidth(line) <= size[0]));
      assertFalse(
          wrapped.stream().flatMapToInt(String::codePoints).anyMatch(Character::isISOControl));
    }
  }

  @Test
  void activeEditorPreservesUnicodeTextAndCursorAcrossTerminalResizes() throws Exception {
    var controller = new TuiController(service(database()), sequence(650));
    controller.handle(character('l'));
    controller.handle(character('n'));
    type(controller, "Antes 漢字 e\u0301");
    var terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
    var screen = new TerminalScreen(terminal);
    var renderer = new TerminalRenderer();

    TerminalSession.run(
        screen,
        () -> {
          renderer.render(screen, controller);
          assertEquals(new TerminalSize(80, 24), screen.getTerminalSize());

          terminal.setTerminalSize(new TerminalSize(120, 35));
          renderer.render(screen, controller);
          assertEquals(new TerminalSize(120, 35), screen.getTerminalSize());
          type(controller, " depois");
          renderer.render(screen, controller);
          assertEditorTextAndCursor(controller, screen, "Antes 漢字 e\u0301 depois");

          terminal.setTerminalSize(new TerminalSize(60, 20));
          renderer.render(screen, controller);
          assertEquals(new TerminalSize(60, 20), screen.getTerminalSize());
          type(controller, " menor");
          renderer.render(screen, controller);
          assertEditorTextAndCursor(controller, screen, "Antes 漢字 e\u0301 depois menor");
        });
  }

  @Test
  void terminalIsStoppedAndClosedWhenTheEventLoopThrows() {
    AtomicBoolean stopped = new AtomicBoolean();
    AtomicBoolean closed = new AtomicBoolean();
    Screen screen =
        (Screen)
            Proxy.newProxyInstance(
                Screen.class.getClassLoader(),
                new Class<?>[] {Screen.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("stopScreen")) stopped.set(true);
                  if (method.getName().equals("close")) closed.set(true);
                  if (method.getReturnType().equals(boolean.class)) return false;
                  if (method.getReturnType().equals(int.class)) return 0;
                  return null;
                });

    assertThrows(
        IllegalStateException.class,
        () ->
            TerminalSession.run(
                screen,
                () -> {
                  throw new IllegalStateException("injected");
                }));
    assertTrue(stopped.get());
    assertTrue(closed.get());
  }

  @Test
  void filePromptShowsPhysicalNeighborsAndMissingExactLookupOffersNoAlternative() {
    var database = database();
    var service = service(database);
    var left = service.createDraft(List.of());
    var right = service.createDraft(List.of());
    service.installMainCard(left.id(), 0, "3118/2");
    service.installMainCard(right.id(), 0, "3118/3");
    service.createDraft(List.of());
    var controller = new TuiController(service, sequence(700));
    controller.handle(character('l'));
    controller.handle(character('f'));
    type(controller, "3118/2a");
    List<String> prompt = controller.viewLines();
    assertTrue(prompt.contains("Address: 3118/2a"));
    assertTrue(prompt.contains("Previous physical card: 3118/2"));
    assertTrue(prompt.contains("Next physical card: 3118/3"));
    controller.handle(key(KeyType.Enter));
    assertEquals(
        List.of("3118/2", "3118/2a", "3118/3"),
        service.getInstalledInAddressOrder().stream().map(card -> card.addressLiteral()).toList());

    controller.handle(key(KeyType.Escape));
    controller.handle(character('j'));
    controller.handle(character('l'));
    controller.handle(character('g'));
    type(controller, "3118/9");
    controller.handle(key(KeyType.Enter));
    assertEquals(TuiController.Screen.MAIN_ADDRESS, controller.screen());
    assertTrue(controller.status().contains("No Main card is filed at exactly '3118/9'"));
    assertFalse(controller.status().contains("3118/3"));
  }

  @Test
  void indexScreensAuthorEntriesInOrderAndRequireManualMainLookup() {
    var database = database();
    var mainCards = service(database);
    var indexService =
        new IndexService(database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), sequence(900));
    var controller = new TuiController(mainCards, indexService, sequence(1_000));

    controller.handle(character('j'));
    controller.handle(character('j'));
    controller.handle(character('l'));
    controller.handle(character('a'));
    controller.handle(character('+'));
    type(controller, "1");
    controller.handle(key(KeyType.Enter));
    assertEquals(TuiController.Screen.INDEX_LIST_CARD, controller.screen());

    addIndexEntry(controller, "Argument", 'M', "3118/99");
    addIndexEntry(controller, "Aggressivität", 'K', "Argument");
    assertEquals(
        List.of("Argument", "Aggressivität"),
        indexService.getEntries(indexService.getListCards('A').getFirst().id()).stream()
            .map(entry -> entry.authoredText())
            .toList());

    controller.handle(character('k'));
    controller.handle(key(KeyType.Enter));
    assertTrue(controller.status().contains("3118/99"));
    assertEquals(TuiController.Screen.INDEX_LIST_CARD, controller.screen());
    assertFalse(controller.viewLines().stream().anyMatch(line -> line.contains("suggest")));

    controller.handle(character('p'));
    assertTrue(controller.status().contains("original entry retained"));
    assertEquals("Argument", indexService.getKeytermCards().getFirst().keyterm());
    assertTrue(
        indexService
                .getEntries(indexService.getListCards('A').getFirst().id())
                .getFirst()
                .promotedToKeytermCardId()
            != null);
  }

  @Test
  void bibImportPreviewResearchNotesAndMainExRefRoundTripLiterally() throws Exception {
    var database = database();
    var mainCards = service(database);
    var bibliography =
        new BibliographyService(
            database.unitOfWork(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            sequence(2_000),
            new JBibTeXFileParser());
    var controller = new TuiController(mainCards, null, bibliography, sequence(3_000));
    var bibFile = temp.resolve("papers.bib");
    Files.writeString(
        bibFile,
        "@article{paperkey, author={Müller, Ada}, title={Reading by Hand}, abstract={Imported abstract}}",
        java.nio.charset.StandardCharsets.UTF_8);

    controller.handle(character('j'));
    controller.handle(character('j'));
    controller.handle(character('j'));
    controller.handle(character('l'));
    controller.handle(character('i'));
    type(controller, bibFile.toString());
    controller.handle(key(KeyType.Enter));
    type(controller, "local-papers");
    controller.handle(key(KeyType.Enter));
    assertEquals(TuiController.Screen.BIB_IMPORT_PREVIEW, controller.screen());
    assertTrue(controller.viewLines().stream().anyMatch(line -> line.contains("NEW")));
    assertTrue(
        controller.viewLines().stream().anyMatch(line -> line.contains("no changes applied")));
    assertTrue(bibliography.getSources().isEmpty());

    controller.handle(character('a'));
    assertTrue(controller.status().contains("applied atomically"));
    assertEquals(1, bibliography.getSources().size());
    var source = bibliography.getSources().getFirst();
    assertTrue(bibliography.findBibCardForSource(source.id()).isEmpty());
    controller.handle(key(KeyType.Escape));
    controller.handle(key(KeyType.Enter));
    assertEquals(TuiController.Screen.BIB_SOURCE, controller.screen());
    assertTrue(
        controller.viewLines().stream().anyMatch(line -> line.contains("Citation key: paperkey")));

    controller.handle(character('c'));
    type(controller, "Read for an argument");
    controller.handle(key(KeyType.Enter));
    type(controller, "The source overview is my authored summary.");
    controller.handle(key(KeyType.Enter));
    assertEquals(TuiController.Screen.BIB_CARD, controller.screen());
    assertTrue(
        controller.viewLines().stream()
            .anyMatch(line -> line.contains("Reading goal: Read for an argument")));

    controller.handle(character('l'));
    controller.handle(character('a'));
    controller.handle(character('p'));
    type(controller, "27");
    controller.handle(key(KeyType.Enter));
    type(controller, "A brief authored observation.");
    controller.handle(key(KeyType.Enter));
    assertTrue(
        controller.viewLines().stream()
            .anyMatch(line -> line.contains("(27) A brief authored observation.")));
    assertEquals(
        BibNote.LocatorType.PAGE,
        bibliography
            .getBibNotes(bibliography.findBibCardForSource(source.id()).orElseThrow().id())
            .getFirst()
            .locatorType());

    controller.handle(key(KeyType.Escape));
    controller.handle(key(KeyType.Escape));
    controller.handle(key(KeyType.Escape));
    controller.handle(character('k'));
    controller.handle(character('k'));
    controller.handle(character('k'));
    controller.handle(character('l'));
    controller.handle(character('n'));
    controller.handle(key(KeyType.Escape));
    controller.handle(character('e'));
    type(controller, "b.paperkey, p. 27");
    controller.handle(key(KeyType.Enter));
    type(controller, ":w");
    controller.handle(key(KeyType.Enter));
    controller.handle(key(KeyType.Escape));
    controller.handle(character('f'));
    type(controller, "3118/27");
    controller.handle(key(KeyType.Enter));
    controller.handle(key(KeyType.Escape));
    controller.handle(character('j'));
    controller.handle(character('l'));
    assertTrue(controller.viewLines().contains("b.paperkey, p. 27"));

    var reopened = SqliteDatabase.open(database.path());
    var reopenedBibliography =
        new BibliographyService(
            reopened.unitOfWork(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            sequence(9_000),
            new JBibTeXFileParser());
    var retainedCard = reopenedBibliography.findBibCardForSource(source.id()).orElseThrow();
    assertEquals(
        "A brief authored observation.",
        reopenedBibliography.getBibNotes(retainedCard.id()).getFirst().authoredText());
    assertEquals(
        "b.paperkey, p. 27",
        reopened
            .unitOfWork()
            .transact(
                tx ->
                    tx.mainCards()
                        .findInstalledInAddressOrder()
                        .getFirst()
                        .authoredBody()
                        .getFirst()
                        .literal()));
  }

  private SqliteDatabase database() {
    return SqliteDatabase.create(
        temp.resolve("tui.db"), Clock.fixed(NOW, ZoneOffset.UTC), sequence(1));
  }

  private static MainCardService service(SqliteDatabase database) {
    return new MainCardService(
        database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), sequence(100));
  }

  private static int eventCount(SqliteDatabase database) {
    return database.unitOfWork().transact(tx -> tx.events().findAfter(0, 100).size());
  }

  private static java.util.function.Supplier<UUID> sequence(long start) {
    AtomicLong counter = new AtomicLong(start);
    return () -> new UUID(0, counter.incrementAndGet());
  }

  private static KeyStroke character(char value) {
    return new KeyStroke(value, false, false);
  }

  private static KeyStroke key(KeyType keyType) {
    return new KeyStroke(keyType);
  }

  private static void type(TuiController controller, String value) {
    value.chars().forEach(character -> controller.handle(character((char) character)));
  }

  private static void assertEditorTextAndCursor(
      TuiController controller, Screen screen, String expectedText) {
    assertEquals(expectedText, controller.editor().renderedText());
    assertEquals(expectedText.length(), controller.editor().cursorUtf16Offset());
    TerminalPosition cursor = screen.getCursorPosition();
    assertTrue(
        cursor.getColumn() >= 0 && cursor.getColumn() < screen.getTerminalSize().getColumns());
    assertTrue(cursor.getRow() >= 0 && cursor.getRow() < screen.getTerminalSize().getRows());
  }

  private static void addIndexEntry(
      TuiController controller, String authoredText, char targetKind, String literal) {
    controller.handle(character('a'));
    type(controller, authoredText);
    controller.handle(key(KeyType.Enter));
    controller.handle(character(targetKind));
    type(controller, literal);
    controller.handle(key(KeyType.Enter));
  }
}
