package org.zettelkasten.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.zettelkasten.application.IndexService;
import org.zettelkasten.application.MainCardService;
import org.zettelkasten.infrastructure.sqlite.SqliteDatabase;
import org.zettelkasten.tui.TuiApplication;

/**
 * Offline command-line entry point. Operational commands are added by their implementation issues.
 */
public final class Main {
  private static final String VERSION = "0.1.0-SNAPSHOT";
  private static final String[] COMMANDS = {"init", "open", "doctor", "backup", "export"};

  private Main() {}

  public static void main(String[] args) {
    int exitCode = run(args, System.out, System.err);
    if (exitCode != 0) System.exit(exitCode);
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0
        || Arrays.asList(args).contains("--help")
        || Arrays.asList(args).contains("-h")) {
      printHelp(out);
      return 0;
    }
    if (Arrays.asList(args).contains("--version")) {
      out.println("zettelkasten " + VERSION + " (Java 25 required)");
      return 0;
    }
    String command = args[0];
    if ("open".equals(command)) return openArchive(args, out, err);
    if (Arrays.asList(COMMANDS).contains(command)) {
      err.printf(
          "Command '%s' is not implemented yet (tracked by ZK-01). No files were opened or created.%n",
          command);
      return 2;
    }
    err.printf("Unknown command: %s%n", command);
    printHelp(err);
    return 2;
  }

  private static int openArchive(String[] args, PrintStream out, PrintStream err) {
    Path databasePath = null;
    for (int index = 1; index < args.length; index++) {
      if ("--db".equals(args[index]) && index + 1 < args.length) {
        databasePath = Path.of(args[++index]);
      } else {
        err.println("Usage: zettelkasten open --db FILE");
        return 2;
      }
    }
    if (databasePath == null) {
      err.println("Usage: zettelkasten open --db FILE");
      err.println("Open requires an existing SQLite archive; it never creates one.");
      return 2;
    }
    try {
      var database = SqliteDatabase.open(databasePath);
      var cards =
          new MainCardService(database.unitOfWork(), java.time.Clock.systemUTC(), UUID::randomUUID);
      var indexCards =
          new IndexService(database.unitOfWork(), java.time.Clock.systemUTC(), UUID::randomUUID);
      new TuiApplication(cards, indexCards, UUID::randomUUID).run();
      return 0;
    } catch (IOException | RuntimeException exception) {
      err.println("Could not open terminal session: " + exception.getMessage());
      return 1;
    }
  }

  private static void printHelp(PrintStream out) {
    out.println("Zettelkasten — analog-first, local-first terminal slip-box");
    out.println("Usage: zettelkasten <command> [options]");
    out.println();
    out.println("Commands (implementation is tracked by ZK-01 through ZK-16):");
    out.println("  init       Initialize a local SQLite archive (planned)");
    out.println("  open       Open an existing archive: open --db FILE");
    out.println("  doctor     Check archive integrity (planned)");
    out.println("  backup     Create a consistent SQLite backup (planned)");
    out.println("  export     Export a portable archive (planned)");
    out.println();
    out.println("Use --version to print the application version. Java 25 is required.");
    out.println("No network access or database creation occurs for --help.");
  }
}
