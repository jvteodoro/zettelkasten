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
  void plannedCommandFailsHonestlyWithoutTouchingPaths() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    assertEquals(
        2,
        Main.run(
            new String[] {"init", "--db", "/tmp/should-not-exist.db"}, stream(out), stream(err)));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("No files were opened or created"));
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

  private static PrintStream stream(ByteArrayOutputStream output) {
    return new PrintStream(output, true, StandardCharsets.UTF_8);
  }
}
