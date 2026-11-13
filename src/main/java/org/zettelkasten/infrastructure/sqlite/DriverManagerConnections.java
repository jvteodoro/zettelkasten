package org.zettelkasten.infrastructure.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteConfig;

/** JDBC connections used by archive validation before a destination store exists. */
final class DriverManagerConnections {
  private DriverManagerConnections() {}

  static Connection open(Path path) throws SQLException {
    SQLiteConfig config = new SQLiteConfig();
    config.enforceForeignKeys(true);
    config.setBusyTimeout(5_000);
    Connection connection = config.createConnection("jdbc:sqlite:" + path);
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      return connection;
    } catch (SQLException exception) {
      connection.close();
      throw exception;
    }
  }

  static Connection temporarySchema() throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    try {
      new MigrationRunner().migrate(connection);
      return connection;
    } catch (SQLException exception) {
      connection.close();
      throw exception;
    }
  }
}
