package org.zettelkasten.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.zettelkasten.application.BibliographyService;
import org.zettelkasten.application.IndexService;
import org.zettelkasten.application.MainCardService;
import org.zettelkasten.infrastructure.bibtex.JBibTeXFileParser;
import org.zettelkasten.infrastructure.sqlite.ArchiveOperations;
import org.zettelkasten.infrastructure.sqlite.GraphProjectionExporter;
import org.zettelkasten.infrastructure.sqlite.SqliteDatabase;
import org.zettelkasten.tui.TuiApplication;

/**
 * Offline command-line entry point. Operational commands are added by their implementation issues.
 */
public final class Main {
  private static final String VERSION = "0.1.0-SNAPSHOT";
  private static final String[] COMMANDS = {
    "init",
    "open",
    "doctor",
    "backup",
    "restore",
    "export",
    "import-archive",
    "migrate",
    "graph-export"
  };

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
    if (Arrays.asList(COMMANDS).contains(command)) return maintenance(command, args, out, err);
    err.printf("Unknown command: %s%n", command);
    printHelp(err);
    return 2;
  }

  private static int maintenance(String command, String[] args, PrintStream out, PrintStream err) {
    try {
      return switch (command) {
        case "init" -> {
          Path db = option(args, "--db");
          SqliteDatabase.create(db);
          out.println("Initialized local archive: " + db.toAbsolutePath().normalize());
          yield 0;
        }
        case "doctor" -> {
          Path db = option(args, "--db");
          var report = new ArchiveOperations(SqliteDatabase.open(db)).doctor();
          out.println("Schema version: " + report.schemaVersion());
          out.println("SQLite integrity: " + report.integrity());
          out.println("Last event sequence: " + report.lastEventSequence());
          for (String problem : report.problems()) err.println("ERROR: " + problem);
          out.println(report.healthy() ? "Archive is healthy." : "Archive has integrity problems.");
          yield report.healthy() ? 0 : 1;
        }
        case "backup" -> {
          Path db = option(args, "--db");
          Path output = option(args, "--out");
          out.println(ArchiveOperations.backupExisting(db, output));
          yield 0;
        }
        case "restore" -> {
          Path source = option(args, "--from");
          Path destination = option(args, "--db");
          out.println(ArchiveOperations.backupExisting(source, destination));
          yield 0;
        }
        case "export" -> {
          Path db = option(args, "--db");
          Path output = option(args, "--out");
          out.println(new ArchiveOperations(SqliteDatabase.open(db)).export(output));
          yield 0;
        }
        case "graph-export" -> {
          Path db = option(args, "--db");
          Path output = option(args, "--out");
          out.println(new GraphProjectionExporter(SqliteDatabase.open(db)).export(output));
          yield 0;
        }
        case "import-archive" -> {
          Path source = option(args, "--from");
          Path destination = option(args, "--db");
          out.println(ArchiveOperations.importArchive(source, destination).path());
          yield 0;
        }
        case "migrate" -> migrate(args, out, err);
        default -> throw new IllegalStateException("unexpected command");
      };
    } catch (RuntimeException exception) {
      err.println("Archive command failed: " + exception.getMessage());
      return 1;
    }
  }

  private static int migrate(String[] args, PrintStream out, PrintStream err) {
    Path database = option(args, "--db");
    boolean dryRun = Arrays.asList(args).contains("--dry-run");
    boolean apply = Arrays.asList(args).contains("--apply");
    if (dryRun == apply || args.length != 4) {
      err.println("Usage: zettelkasten migrate --db FILE --dry-run|--apply");
      return 2;
    }
    var plan = ArchiveOperations.pendingMigrations(database);
    if (plan.isEmpty()) {
      out.println("No migrations are pending.");
      return 0;
    }
    out.println("Pending migrations:");
    plan.forEach(item -> out.println("- " + item));
    if (dryRun) return 0;
    int previousVersion = ArchiveOperations.schemaVersion(database);
    Path safetyCopy =
        database.resolveSibling(
            database.getFileName() + ".pre-migration-v" + previousVersion + ".db");
    out.println("Safety backup: " + ArchiveOperations.backupBeforeMigration(database, safetyCopy));
    SqliteDatabase.migrate(database);
    var opened = SqliteDatabase.open(database);
    var report = new ArchiveOperations(opened).doctor();
    out.println("Applied migrations; schema version is now " + report.schemaVersion() + ".");
    if (!report.healthy()) {
      report.problems().forEach(problem -> err.println("ERROR: " + problem));
      return 1;
    }
    return 0;
  }

  private static Path option(String[] args, String name) {
    for (int i = 1; i < args.length - 1; i++) {
      if (name.equals(args[i])) return Path.of(args[i + 1]);
    }
    throw new IllegalArgumentException("Missing required option " + name);
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
      var bibliography =
          new BibliographyService(
              database.unitOfWork(),
              java.time.Clock.systemUTC(),
              UUID::randomUUID,
              new JBibTeXFileParser());
      new TuiApplication(cards, indexCards, bibliography, UUID::randomUUID).run();
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
    out.println("Commands:");
    out.println("  init       Initialize a local SQLite archive: init --db FILE");
    out.println("  open       Open an existing archive: open --db FILE");
    out.println("  doctor     Check archive integrity: doctor --db FILE");
    out.println("  backup     Create a consistent SQLite backup: backup --db FILE --out BACKUP.db");
    out.println("  restore    Restore to a new file: restore --from BACKUP.db --db NEWFILE");
    out.println("  export     Export a portable archive: export --db FILE --out DIRECTORY");
    out.println(
        "  graph-export  Export an offline graph projection: graph-export --db FILE --out DIRECTORY");
    out.println(
        "  import-archive  Import to a new file: import-archive --from DIRECTORY --db NEWFILE");
    out.println("  migrate    Review/apply schema changes: migrate --db FILE --dry-run|--apply");
    out.println();
    out.println("Use --version to print the application version. Java 25 is required.");
    out.println("No network access or database creation occurs for --help.");
  }
}
