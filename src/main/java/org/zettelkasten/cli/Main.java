package org.zettelkasten.cli;

import java.io.PrintStream;
import java.util.Arrays;

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

  private static void printHelp(PrintStream out) {
    out.println("Zettelkasten — analog-first, local-first terminal slip-box");
    out.println("Usage: zettelkasten <command> [options]");
    out.println();
    out.println("Commands (implementation is tracked by ZK-01 through ZK-16):");
    out.println("  init       Initialize a local SQLite archive (planned)");
    out.println("  open       Open the Desk/Main/Index/Bib terminal interface (planned)");
    out.println("  doctor     Check archive integrity (planned)");
    out.println("  backup     Create a consistent SQLite backup (planned)");
    out.println("  export     Export a portable archive (planned)");
    out.println();
    out.println("Use --version to print the application version. Java 25 is required.");
    out.println("No network access or database creation occurs for --help.");
  }
}
