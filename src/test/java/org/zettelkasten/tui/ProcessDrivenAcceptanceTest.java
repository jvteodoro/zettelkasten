package org.zettelkasten.tui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zettelkasten.infrastructure.sqlite.SqliteDatabase;

class ProcessDrivenAcceptanceTest {
  @TempDir Path temp;

  @Test
  void childJvmDrivesDeskMainIndexBibCrashReopenAndPortableArchive() throws Exception {
    Path database = temp.resolve("process.db");
    Path bibFile = temp.resolve("reading.bib");
    Files.writeString(
        bibFile,
        "@article{paperkey, author={Müller, Ada}, title={Reading by Hand}, abstract={Not a note}}",
        StandardCharsets.UTF_8);
    SqliteDatabase.create(database);

    String workflow =
        runJava(
            ProcessTuiHarness.class.getName(),
            "workflow",
            database.toString(),
            bibFile.toString(),
            "80",
            "24");
    assertTrue(workflow.contains("TUI_WORKFLOW_OK terminal=80x24"), workflow);

    String crash = runJava(ProcessTuiHarness.class.getName(), "crash", database.toString());
    assertTrue(crash.contains("COMMIT_COMPLETE_EXITING_WITH_WAL_OPEN"), crash);
    Path wal = database.resolveSibling(database.getFileName() + "-wal");
    assertTrue(
        Files.isRegularFile(wal) && Files.size(wal) > 0,
        "crash did not leave committed WAL data: " + crash);

    assertTrue(runCli("doctor", "--db", database.toString()).contains("Archive is healthy."));
    Path backup = temp.resolve("process-backup.db");
    runCli("backup", "--db", database.toString(), "--out", backup.toString());
    Path restored = temp.resolve("process-restored.db");
    runCli("restore", "--from", backup.toString(), "--db", restored.toString());
    assertTrue(runCli("doctor", "--db", restored.toString()).contains("Archive is healthy."));

    Path exported = temp.resolve("process-export");
    runCli("export", "--db", restored.toString(), "--out", exported.toString());
    Path imported = temp.resolve("process-import.db");
    runCli("import-archive", "--from", exported.toString(), "--db", imported.toString());
    String reopened =
        runJava(ProcessTuiHarness.class.getName(), "verify", imported.toString(), "120", "35");
    assertTrue(reopened.contains("REOPEN_VERIFY_OK terminal=120x35"), reopened);
  }

  private String runCli(String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-cp");
    command.add(testClasspath());
    command.add("org.zettelkasten.cli.Main");
    command.addAll(List.of(args));
    return run(command.toArray(String[]::new));
  }

  private String runJava(String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-cp");
    command.add(testClasspath());
    command.addAll(List.of(args));
    return run(command.toArray(String[]::new));
  }

  private String run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    if (!process.waitFor(
        Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("Process timed out: " + List.of(command));
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.exitValue() != 0)
      throw new AssertionError("Process failed with " + process.exitValue() + ": " + output);
    return output;
  }

  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static String testClasspath() {
    return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
  }
}
