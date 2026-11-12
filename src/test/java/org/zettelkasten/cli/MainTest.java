package org.zettelkasten.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
  @TempDir Path temp;

  @Test
  void helpDoesNotRequireOrCreateAnArchive() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    assertEquals(0, Main.run(new String[] {"--help"}, stream(out), stream(err)));
    assertTrue(
        out.toString(StandardCharsets.UTF_8).contains("No network access or database creation"));
    assertEquals(0, err.size());
  }

  @Test
  void initCreatesAnArchiveAndRefusesToOverwriteIt() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var path = temp.resolve("initialized.db");
    assertEquals(
        0, Main.run(new String[] {"init", "--db", path.toString()}, stream(out), stream(err)));
    assertTrue(Files.isRegularFile(path));
    assertEquals(
        1, Main.run(new String[] {"init", "--db", path.toString()}, stream(out), stream(err)));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("Refusing to overwrite"));
  }

  @Test
  void unknownCommandIsRejected() {
    var err = new ByteArrayOutputStream();
    assertEquals(
        2, Main.run(new String[] {"search"}, stream(new ByteArrayOutputStream()), stream(err)));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown command"));
  }

  @Test
  void openRequiresAnExistingArchiveAndNeverCreatesItImplicitly() {
    Path missing = temp.resolve("missing.db");
    var err = new ByteArrayOutputStream();
    assertEquals(
        1,
        Main.run(
            new String[] {"open", "--db", missing.toString()},
            stream(new ByteArrayOutputStream()),
            stream(err)));
    assertTrue(Files.notExists(missing));
  }

  @Test
  void maintenanceCommandsBackupRestoreAndMoveThroughPortableJsonl() {
    Path database = temp.resolve("operations.db");
    Path backup = temp.resolve("operations-backup.db");
    Path restored = temp.resolve("operations-restored.db");
    Path exported = temp.resolve("portable");
    Path imported = temp.resolve("portable-import.db");
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();

    assertEquals(
        0, Main.run(new String[] {"init", "--db", database.toString()}, stream(out), stream(err)));
    assertEquals(
        0,
        Main.run(new String[] {"doctor", "--db", database.toString()}, stream(out), stream(err)));
    assertEquals(
        0,
        Main.run(
            new String[] {"backup", "--db", database.toString(), "--out", backup.toString()},
            stream(out),
            stream(err)));
    assertEquals(
        0,
        Main.run(
            new String[] {"restore", "--from", backup.toString(), "--db", restored.toString()},
            stream(out),
            stream(err)));
    assertEquals(
        0,
        Main.run(
            new String[] {"export", "--db", database.toString(), "--out", exported.toString()},
            stream(out),
            stream(err)));
    assertEquals(
        0,
        Main.run(
            new String[] {
              "import-archive", "--from", exported.toString(), "--db", imported.toString()
            },
            stream(out),
            stream(err)));
    assertTrue(Files.isRegularFile(restored));
    assertTrue(Files.isRegularFile(imported));
    assertTrue(Files.isRegularFile(exported.resolve("manifest.json")));
    assertEquals(0, err.size());
  }

  private static PrintStream stream(ByteArrayOutputStream output) {
    return new PrintStream(output, true, StandardCharsets.UTF_8);
  }
}
