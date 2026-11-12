package org.zettelkasten.infrastructure.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Applies immutable, ordered SQL migrations and refuses changed or future schemas. */
final class MigrationRunner {
  static final int CURRENT_VERSION = 3;
  private static final List<Migration> MIGRATIONS =
      List.of(
          new Migration(1, "canonical SQLite schema", "/db/migration/V001__canonical_sqlite.sql"),
          new Migration(
              2,
              "Index promotion provenance and append-only entries",
              "/db/migration/V002__index_promotion_provenance.sql"),
          new Migration(
              3,
              "Bibliography append-only notes and import provenance",
              "/db/migration/V003__bibliography_append_only.sql"));

  int migrate(Connection connection) throws SQLException {
    int currentVersion = currentVersion(connection);
    int knownVersion = MIGRATIONS.getLast().version();
    if (currentVersion > knownVersion) {
      throw new SQLException(
          "Database schema version " + currentVersion + " is newer than this application");
    }
    verifyAppliedChecksums(connection, currentVersion);
    for (Migration migration : MIGRATIONS) {
      if (migration.version() <= currentVersion) continue;
      if (migration.version() != currentVersion + 1) {
        throw new SQLException("Missing migration after schema version " + currentVersion);
      }
      byte[] bytes = load(migration.resource());
      executeScript(connection, new String(bytes, StandardCharsets.UTF_8));
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO schema_migrations(version, description, checksum_sha256, applied_at) VALUES(?,?,?,?)")) {
        insert.setInt(1, migration.version());
        insert.setString(2, migration.description());
        insert.setString(3, sha256(bytes));
        insert.setString(4, Instant.now().toString());
        insert.executeUpdate();
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA user_version = " + migration.version());
      }
      currentVersion = migration.version();
    }
    return currentVersion;
  }

  void requireCurrent(Connection connection) throws SQLException {
    int current = currentVersion(connection);
    int known = MIGRATIONS.getLast().version();
    if (current != known) {
      throw new SQLException(
          current < known
              ? "Archive schema version "
                  + current
                  + " requires explicit migration; run migrate --dry-run then migrate --apply"
              : "Database schema version " + current + " is newer than this application");
    }
    verifyAppliedChecksums(connection, current);
  }

  int currentVersion(Connection connection) throws SQLException {
    if (!tableExists(connection, "schema_migrations")) return 0;
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
      return result.next() ? result.getInt(1) : 0;
    }
  }

  List<String> pendingDescriptions(int currentVersion) {
    if (currentVersion > MIGRATIONS.getLast().version())
      throw new IllegalArgumentException(
          "Database schema version " + currentVersion + " is newer than this application");
    return MIGRATIONS.stream()
        .filter(migration -> migration.version() > currentVersion)
        .map(Migration::description)
        .toList();
  }

  private void verifyAppliedChecksums(Connection connection, int version) throws SQLException {
    for (Migration migration : MIGRATIONS) {
      if (migration.version() > version) continue;
      try (var query =
          connection.prepareStatement(
              "SELECT checksum_sha256 FROM schema_migrations WHERE version = ?")) {
        query.setInt(1, migration.version());
        try (ResultSet result = query.executeQuery()) {
          if (!result.next())
            throw new SQLException("Missing applied migration record " + migration.version());
          String expected = sha256(load(migration.resource()));
          if (!expected.equals(result.getString(1))) {
            throw new SQLException("Migration checksum mismatch at version " + migration.version());
          }
        }
      }
    }
  }

  private static boolean tableExists(Connection connection, String table) throws SQLException {
    try (var query =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
      query.setString(1, table);
      try (ResultSet result = query.executeQuery()) {
        return result.next();
      }
    }
  }

  private static byte[] load(String resource) throws SQLException {
    try (InputStream input = MigrationRunner.class.getResourceAsStream(resource)) {
      if (input == null) throw new SQLException("Missing migration resource " + resource);
      return input.readAllBytes();
    } catch (IOException exception) {
      throw new SQLException("Could not read migration resource " + resource, exception);
    }
  }

  private static String sha256(byte[] bytes) throws SQLException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new SQLException("SHA-256 is unavailable", exception);
    }
  }

  private static void executeScript(Connection connection, String script) throws SQLException {
    StringBuilder current = new StringBuilder();
    boolean singleQuoted = false;
    boolean doubleQuoted = false;
    for (String line : script.split("\\R", -1)) {
      if (current.toString().isBlank() && line.stripLeading().startsWith("--")) continue;
      for (int index = 0; index < line.length(); index++) {
        char character = line.charAt(index);
        if (character == '\'' && !doubleQuoted) {
          if (singleQuoted && index + 1 < line.length() && line.charAt(index + 1) == '\'') {
            current.append("''");
            index++;
            continue;
          }
          singleQuoted = !singleQuoted;
        } else if (character == '"' && !singleQuoted) {
          doubleQuoted = !doubleQuoted;
        }
        current.append(character);
        if (character == ';' && !singleQuoted && !doubleQuoted && statementComplete(current)) {
          execute(connection, current.toString());
          current.setLength(0);
        }
      }
      current.append('\n');
    }
    if (!current.toString().isBlank()) {
      throw new SQLException("Migration contains an unterminated SQL statement");
    }
  }

  private static boolean statementComplete(StringBuilder sql) {
    String normalized = sql.toString().stripLeading().toUpperCase(java.util.Locale.ROOT);
    if (!normalized.startsWith("CREATE TRIGGER")) return true;
    return normalized.matches("(?s).*\\bEND\\s*;\\s*");
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    if (sql.isBlank() || sql.stripLeading().startsWith("--")) return;
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private record Migration(int version, String description, String resource) {}
}
