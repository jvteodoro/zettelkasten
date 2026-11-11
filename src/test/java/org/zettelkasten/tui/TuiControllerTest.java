package org.zettelkasten.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zettelkasten.application.MainCardService;
import org.zettelkasten.domain.CardLinkRun;
import org.zettelkasten.infrastructure.sqlite.SqliteDatabase;

class TuiControllerTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @TempDir java.nio.file.Path temp;

  @Test
  void deskEditorSavesTypedLiteralLinkAndManualInstallWithoutFollowingIt() {
    var database = database();
    var service = service(database);
    var controller = new TuiController(service, sequence(300));
    controller.handle(character('d'));
    assertEquals(TuiController.Screen.DESK, controller.screen());
    controller.handle(character('n'));
    assertEquals(TuiController.Screen.EDITOR, controller.screen());
    controller.handle(character('l'));
    controller.handle(ctrl('l'));
    type(controller, "3118/2a");
    controller.handle(key(KeyType.Enter));
    assertEquals(1, eventCount(database));
    controller.handle(ctrl('s'));
    assertEquals(2, eventCount(database));
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
    controller.handle(character('m'));
    controller.handle(key(KeyType.Enter));
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
  void unsavedExitRequiresAnExplicitChoiceAndCtrlCDoesNotDiscard() {
    var database = database();
    var service = service(database);
    var controller = new TuiController(service, sequence(400));
    controller.handle(character('d'));
    controller.handle(character('n'));
    controller.handle(character('x'));
    controller.handle(new KeyStroke((char) 3, true, false));
    assertTrue(controller.status().contains("no card data was discarded"));
    controller.handle(key(KeyType.Escape));
    assertEquals(TuiController.Screen.CONFIRM_DISCARD, controller.screen());
    controller.handle(character('n'));
    assertEquals(TuiController.Screen.DESK, controller.screen());
    assertEquals(1, service.getDrafts().size());
    assertTrue(service.getDrafts().getFirst().authoredBody().isEmpty());
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

  private static KeyStroke ctrl(char value) {
    return new KeyStroke(value, true, false);
  }

  private static KeyStroke key(KeyType keyType) {
    return new KeyStroke(keyType);
  }

  private static void type(TuiController controller, String value) {
    value.chars().forEach(character -> controller.handle(character((char) character)));
  }
}
