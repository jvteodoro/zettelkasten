package org.zettelkasten.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.zettelkasten.application.BibliographyService;
import org.zettelkasten.application.IndexService;
import org.zettelkasten.application.MainCardService;
import org.zettelkasten.domain.ExRefRun;
import org.zettelkasten.infrastructure.bibtex.JBibTeXFileParser;
import org.zettelkasten.infrastructure.sqlite.SqliteDatabase;

/** Child-JVM terminal harness used by the process-level acceptance test. */
public final class ProcessTuiHarness {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  private ProcessTuiHarness() {}

  public static void main(String[] args) throws Exception {
    Path databasePath = Path.of(args[1]);
    switch (args[0]) {
      case "workflow" ->
          runWorkflow(
              databasePath, Path.of(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
      case "crash" -> crashAfterCommit(databasePath);
      case "verify" ->
          verifyReopened(databasePath, Integer.parseInt(args[2]), Integer.parseInt(args[3]));
      default -> throw new IllegalArgumentException("Unknown process scenario");
    }
  }

  private static void runWorkflow(Path path, Path bibFile, int columns, int rows) throws Exception {
    var database = SqliteDatabase.open(path);
    var ids = ids(1000);
    var cards = new MainCardService(database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), ids);
    var index = new IndexService(database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), ids);
    var bibliography =
        new BibliographyService(
            database.unitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC), ids, new JBibTeXFileParser());
    var controller = new TuiController(cards, index, bibliography, ids);
    var terminal = new DefaultVirtualTerminal(new TerminalSize(columns, rows));
    var screen = new TerminalScreen(terminal);
    for (KeyStroke key : workflowKeys(bibFile)) terminal.addInput(key);
    TerminalSession.run(
        screen,
        () -> {
          while (!controller.quitRequested()) {
            new TerminalRenderer().render(screen, controller);
            KeyStroke key = screen.readInput();
            if (key != null) controller.handle(key);
          }
        });

    var reopened = SqliteDatabase.open(path);
    var installed =
        new MainCardService(reopened.unitOfWork(), Clock.systemUTC(), ids(3000))
            .getInstalledInAddressOrder();
    var bibService =
        new BibliographyService(
            reopened.unitOfWork(), Clock.systemUTC(), ids(4000), new JBibTeXFileParser());
    var sources = bibService.getSources();
    var bibCard = bibService.findBibCardForSource(sources.getFirst().id()).orElseThrow();
    var notes = bibService.getBibNotes(bibCard.id());
    var indexService = new IndexService(reopened.unitOfWork(), Clock.systemUTC(), ids(5000));
    boolean exRefRetained =
        installed.getFirst().authoredBody().stream()
            .filter(ExRefRun.class::isInstance)
            .map(ExRefRun.class::cast)
            .anyMatch(run -> run.literal().equals("b.paperkey, p. 27"));
    boolean duplicateAddressStayedDraft =
        new MainCardService(reopened.unitOfWork(), Clock.systemUTC(), ids(6000)).getDrafts().size()
            == 1;
    if (installed.size() != 1
        || sources.size() != 1
        || notes.size() != 1
        || !notes.getFirst().authoredText().equals("A process-authored observation.")
        || indexService.getListCards('A').size() != 1
        || indexService.getEntries(indexService.getListCards('A').getFirst().id()).size() != 1
        || !exRefRetained
        || !duplicateAddressStayedDraft) {
      throw new AssertionError("Process-driven TUI workflow did not preserve the expected records");
    }
    System.out.printf("TUI_WORKFLOW_OK terminal=%dx%d%n", columns, rows);
  }

  private static List<KeyStroke> workflowKeys(Path bibFile) {
    List<KeyStroke> keys = new ArrayList<>();
    addCharacters(keys, "dnÜberblick 漢字 e\u0301");
    keys.add(control('e'));
    addCharacters(keys, "b.paperkey, p. 27");
    keys.add(key(KeyType.Enter));
    keys.add(control('s'));
    keys.add(key(KeyType.Escape));
    keys.add(character('f'));
    addCharacters(keys, "3118/1");
    keys.add(key(KeyType.Enter));

    keys.add(character('n'));
    addCharacters(keys, "Duplicate address remains a draft.");
    keys.add(control('s'));
    keys.add(key(KeyType.Escape));
    keys.add(character('f'));
    addCharacters(keys, "3118/1");
    keys.add(key(KeyType.Enter));
    keys.add(key(KeyType.Escape));

    keys.add(character('i'));
    keys.add(character('a'));
    keys.add(character('+'));
    addCharacters(keys, "1");
    keys.add(key(KeyType.Enter));
    keys.add(character('a'));
    addCharacters(keys, "Überprüfung");
    keys.add(key(KeyType.Enter));
    keys.add(character('m'));
    addCharacters(keys, "3118/1");
    keys.add(key(KeyType.Enter));
    keys.add(key(KeyType.Escape));
    keys.add(key(KeyType.Escape));

    keys.add(character('b'));
    keys.add(character('i'));
    addCharacters(keys, bibFile.toString());
    keys.add(key(KeyType.Enter));
    addCharacters(keys, "process-fixture");
    keys.add(key(KeyType.Enter));
    keys.add(character('a'));
    keys.add(key(KeyType.Escape));
    keys.add(key(KeyType.Enter));
    keys.add(character('c'));
    addCharacters(keys, "Check an argument");
    keys.add(key(KeyType.Enter));
    addCharacters(keys, "An authored source overview.");
    keys.add(key(KeyType.Enter));
    keys.add(character('b'));
    keys.add(character('a'));
    keys.add(character('p'));
    addCharacters(keys, "27");
    keys.add(key(KeyType.Enter));
    addCharacters(keys, "A process-authored observation.");
    keys.add(key(KeyType.Enter));
    keys.add(key(KeyType.Escape));
    keys.add(key(KeyType.Escape));
    keys.add(key(KeyType.Escape));
    keys.add(character('q'));
    return List.copyOf(keys);
  }

  private static void crashAfterCommit(Path path) throws Exception {
    var keeper = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    try (var statement = keeper.createStatement()) {
      statement.executeQuery("PRAGMA journal_mode=WAL").close();
      statement.execute("PRAGMA wal_autocheckpoint=0");
    }
    var database = SqliteDatabase.open(path);
    var cards = new MainCardService(database.unitOfWork(), Clock.systemUTC(), ids(7000));
    cards.createDraft(
        List.of(
            new org.zettelkasten.domain.TextRun(
                UUID.randomUUID(), "Committed before process crash.")));
    Path wal = path.resolveSibling(path.getFileName() + "-wal");
    System.out.printf(
        "WAL_BEFORE_HALT exists=%s size=%d%n",
        Files.exists(wal), Files.exists(wal) ? Files.size(wal) : 0);
    System.out.println("COMMIT_COMPLETE_EXITING_WITH_WAL_OPEN");
    System.out.flush();
    Runtime.getRuntime().halt(0);
  }

  private static void verifyReopened(Path path, int columns, int rows) throws Exception {
    var database = SqliteDatabase.open(path);
    var cards = new MainCardService(database.unitOfWork(), Clock.systemUTC(), ids(8000));
    if (cards.getInstalledInAddressOrder().size() != 1 || cards.getDrafts().size() != 2)
      throw new AssertionError("Reopen lost a filed card, rejected duplicate, or committed draft");
    var terminal = new DefaultVirtualTerminal(new TerminalSize(columns, rows));
    terminal.addInput(character('m'));
    terminal.addInput(character('q'));
    var screen = new TerminalScreen(terminal);
    var index = new IndexService(database.unitOfWork(), Clock.systemUTC(), ids(9000));
    var bibliography =
        new BibliographyService(
            database.unitOfWork(), Clock.systemUTC(), ids(10000), new JBibTeXFileParser());
    new TuiApplication(cards, index, bibliography, ids(11000)).run(screen);
    System.out.printf("REOPEN_VERIFY_OK terminal=%dx%d%n", columns, rows);
  }

  private static Supplier<UUID> ids(long start) {
    AtomicLong value = new AtomicLong(start);
    return () -> new UUID(0, value.incrementAndGet());
  }

  private static void addCharacters(List<KeyStroke> keys, String value) {
    value.chars().forEach(codeUnit -> keys.add(character((char) codeUnit)));
  }

  private static KeyStroke character(char value) {
    return new KeyStroke(value, false, false);
  }

  private static KeyStroke control(char value) {
    return new KeyStroke(value, true, false);
  }

  private static KeyStroke key(KeyType type) {
    return new KeyStroke(type);
  }
}
