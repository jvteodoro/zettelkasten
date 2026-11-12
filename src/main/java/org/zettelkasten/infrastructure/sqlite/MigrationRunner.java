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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Applies immutable, ordered SQL migrations and refuses changed or future schemas. */
final class MigrationRunner {
  static final int CURRENT_VERSION = 5;
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
              "/db/migration/V003__bibliography_append_only.sql"),
          new Migration(
              4,
              "Backfill authored reference projections from typed runs",
              "/db/migration/V004__backfill_authored_reference_projections.sql"),
          new Migration(
              5,
              "Auditable Desk draft discard tombstones",
              "/db/migration/V005__draft_discard_tombstones.sql"));

  int migrate(Connection connection) throws SQLException {
    return migrateThrough(connection, MIGRATIONS.getLast().version());
  }

  int migrateThrough(Connection connection, int targetVersion) throws SQLException {
    if (targetVersion < 0 || targetVersion > MIGRATIONS.getLast().version())
      throw new IllegalArgumentException("Requested migration version is unsupported");
    int currentVersion = currentVersion(connection);
    int knownVersion = MIGRATIONS.getLast().version();
    if (targetVersion < currentVersion)
      throw new SQLException("Cannot migrate an archive backwards");
    if (currentVersion > knownVersion) {
      throw new SQLException(
          "Database schema version " + currentVersion + " is newer than this application");
    }
    verifyAppliedChecksums(connection, currentVersion);
    for (Migration migration : MIGRATIONS) {
      if (migration.version() <= currentVersion || migration.version() > targetVersion) continue;
      if (migration.version() != currentVersion + 1) {
        throw new SQLException("Missing migration after schema version " + currentVersion);
      }
      Set<String> existingReferences =
          migration.version() == 4 ? existingReferenceIds(connection) : Set.of();
      byte[] bytes = load(migration.resource());
      executeScript(connection, new String(bytes, StandardCharsets.UTF_8));
      String appliedAt = Instant.now().toString();
      if (migration.version() == 4) appendBackfillEvents(connection, existingReferences, appliedAt);
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO schema_migrations(version, description, checksum_sha256, applied_at) VALUES(?,?,?,?)")) {
        insert.setInt(1, migration.version());
        insert.setString(2, migration.description());
        insert.setString(3, sha256(bytes));
        insert.setString(4, appliedAt);
        insert.executeUpdate();
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA user_version = " + migration.version());
      }
      currentVersion = migration.version();
    }
    return currentVersion;
  }

  private static Set<String> existingReferenceIds(Connection connection) throws SQLException {
    Set<String> ids = new HashSet<>();
    try (Statement query = connection.createStatement();
        ResultSet rows = query.executeQuery("SELECT id FROM refs")) {
      while (rows.next()) ids.add(rows.getString(1));
    }
    return ids;
  }

  private static void appendBackfillEvents(
      Connection connection, Set<String> previousIds, String migrationTime) throws SQLException {
    String select =
        "SELECT id,source_card_id,source_run_id,kind,status,created_at FROM refs ORDER BY sequence";
    String insert =
        "INSERT INTO domain_events(event_id,aggregate_type,aggregate_id,event_type,schema_version,"
            + "happened_at,correlation_id,payload_json) VALUES(?,?,?,'ReferenceProjectionBackfilled',1,?,?,?)";
    try (Statement query = connection.createStatement();
        ResultSet references = query.executeQuery(select);
        var append = connection.prepareStatement(insert)) {
      while (references.next()) {
        String referenceId = references.getString(1);
        if (previousIds.contains(referenceId)) continue;
        String sourceCardId = references.getString(2);
        String sourceRunId = references.getString(3);
        String kind = references.getString(4);
        String status = references.getString(5);
        UUID eventId = stableMigrationId("ReferenceProjectionBackfilled:" + referenceId);
        UUID correlationId =
            stableMigrationId("ReferenceProjectionBackfillCorrelation:" + referenceId);
        append.setString(1, eventId.toString());
        append.setString(2, "MAIN_CARD");
        append.setString(3, sourceCardId);
        append.setString(4, migrationTime);
        append.setString(5, correlationId.toString());
        append.setString(
            6,
            "{\"referenceId\":\""
                + referenceId
                + "\",\"sourceRunId\":\""
                + sourceRunId
                + "\",\"kind\":\""
                + kind
                + "\",\"resolutionStatus\":\""
                + status
                + "\"}");
        append.executeUpdate();
      }
    }
  }

  private static UUID stableMigrationId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
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
