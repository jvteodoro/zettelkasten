package org.zettelkasten.infrastructure.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;
import org.zettelkasten.application.PersistenceException;
import org.zettelkasten.application.port.AuthoredReferenceRepository;
import org.zettelkasten.application.port.MainCardRepository;
import org.zettelkasten.application.port.UnitOfWork;

/** One SQLite connection and transaction shared by all repositories in a callback. */
public final class SqliteUnitOfWork implements UnitOfWork {
  private final SqliteDatabase database;
  private final Committer committer;

  SqliteUnitOfWork(SqliteDatabase database) {
    this(database, Connection::commit);
  }

  SqliteUnitOfWork(SqliteDatabase database, Committer committer) {
    this.database = Objects.requireNonNull(database, "database");
    this.committer = Objects.requireNonNull(committer, "committer");
  }

  @Override
  public <T> T transact(Function<Transaction, T> work) {
    Objects.requireNonNull(work, "work");
    try (Connection connection = database.newConnection()) {
      connection.setAutoCommit(false);
      SqliteEventJournal events =
          new SqliteEventJournal(connection, database.clock(), database.eventIds());
      Transaction transaction =
          new JdbcTransaction(
              new SqliteMainCardRepository(connection, events),
              new SqliteAuthoredReferenceRepository(connection, events),
              new SqliteIndexCardRepository(connection, events),
              new SqliteBibliographyRepository(connection, events),
              events);
      try {
        T result = work.apply(transaction);
        committer.commit(connection);
        return result;
      } catch (RuntimeException | Error failure) {
        rollback(connection, failure);
        throw failure;
      } catch (SQLException failure) {
        rollback(connection, failure);
        throw new PersistenceException("SQLite transaction failed", failure);
      }
    } catch (SQLException exception) {
      throw new PersistenceException("Could not open or commit SQLite transaction", exception);
    }
  }

  private static void rollback(Connection connection, Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  @FunctionalInterface
  interface Committer {
    void commit(Connection connection) throws SQLException;
  }

  private record JdbcTransaction(
      MainCardRepository mainCards,
      AuthoredReferenceRepository references,
      org.zettelkasten.application.port.IndexCardRepository indexCards,
      org.zettelkasten.application.port.BibliographyRepository bibliography,
      org.zettelkasten.application.port.DomainEventRepository events)
      implements Transaction {}
}
