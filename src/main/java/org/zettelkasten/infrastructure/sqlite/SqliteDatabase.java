package org.zettelkasten.infrastructure.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.sqlite.SQLiteConfig;
import org.zettelkasten.application.PersistenceException;

/** Local SQLite database handle. Creation and opening are deliberately separate operations. */
public final class SqliteDatabase {
  private static final int BUSY_TIMEOUT_MILLIS = 5_000;
  private final Path path;

  private SqliteDatabase(Path path) {
    this.path = path;
  }

  /** Create a new archive at an explicit path. Existing paths are never overwritten. */
  public static SqliteDatabase create(Path requestedPath) {
    Path path = normalize(requestedPath);
    if (Files.exists(path))
      throw new PersistenceException("Refusing to overwrite an existing archive: " + path);
    boolean created = false;
    try {
      Path parent = path.getParent();
      if (parent != null) {
        boolean existed = Files.exists(parent);
        Files.createDirectories(parent);
        if (!existed) setPermissions(parent, PosixFilePermissions.fromString("rwx------"));
      }
      if (Files.isSymbolicLink(path))
        throw new PersistenceException("Archive path cannot be a symbolic link");
      try {
        Files.createFile(
            path,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } catch (UnsupportedOperationException exception) {
        Files.createFile(path);
        setPermissions(path, PosixFilePermissions.fromString("rw-------"));
      }
      created = true;
      try (Connection connection = connect(path)) {
        connection.setAutoCommit(false);
        new MigrationRunner().migrate(connection);
        connection.commit();
      }
      return new SqliteDatabase(path);
    } catch (SQLException | IOException exception) {
      if (created) deleteCreatedArchive(path);
      throw new PersistenceException("Could not initialize SQLite archive", exception);
    }
  }

  /** Open an existing regular archive and apply only pending versioned migrations. */
  public static SqliteDatabase open(Path requestedPath) {
    Path path = normalize(requestedPath);
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
      throw new PersistenceException(
          "Archive does not exist as a regular non-symbolic-link file: " + path);
    }
    try (Connection connection = connect(path)) {
      connection.setAutoCommit(false);
      new MigrationRunner().migrate(connection);
      connection.commit();
      return new SqliteDatabase(path);
    } catch (SQLException exception) {
      throw new PersistenceException("Could not open SQLite archive", exception);
    }
  }

  public Path path() {
    return path;
  }

  public SqliteUnitOfWork unitOfWork() {
    return new SqliteUnitOfWork(this);
  }

  int schemaVersion() {
    try (Connection connection = newConnection()) {
      return new MigrationRunner().currentVersion(connection);
    } catch (SQLException exception) {
      throw new PersistenceException("Could not read SQLite schema version", exception);
    }
  }

  Connection newConnection() throws SQLException {
    return connect(path);
  }

  private static Connection connect(Path path) throws SQLException {
    SQLiteConfig config = new SQLiteConfig();
    config.enforceForeignKeys(true);
    config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    Connection connection = config.createConnection("jdbc:sqlite:" + path);
    boolean successful = false;
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys = ON");
      statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
      try (ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
        if (!result.next() || result.getInt(1) != 1)
          throw new SQLException("SQLite foreign keys are disabled");
      }
      successful = true;
      return connection;
    } finally {
      if (!successful) connection.close();
    }
  }

  private static Path normalize(Path requestedPath) {
    if (requestedPath == null) throw new IllegalArgumentException("Database path is required");
    Path path = requestedPath.toAbsolutePath().normalize();
    if (path.getFileName() == null)
      throw new IllegalArgumentException("Database path must name a file");
    return path;
  }

  private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
    try {
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException | IOException ignored) {
      // Filesystems without POSIX mode support retain their native permission policy.
    }
  }

  private static void deleteCreatedArchive(Path path) {
    try {
      Files.deleteIfExists(path.resolveSibling(path.getFileName() + "-wal"));
      Files.deleteIfExists(path.resolveSibling(path.getFileName() + "-shm"));
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // The original initialization exception is more useful than cleanup failure.
    }
  }
}
