package io.github.federiconbaez.banking.adapter.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

/**
 * Una conexión JDBC a un archivo SQLite con transacciones {@code BEGIN IMMEDIATE}. Dentro del
 * proceso serializa con un lock; entre procesos manda el lock de escritura del archivo (con
 * busy_timeout para esperar en vez de fallar).
 */
final class SqliteSession implements AutoCloseable {
  static final int SCHEMA_VERSION = 1;

  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  private final Object lock = new Object();
  private final Connection connection;

  private SqliteSession(Connection connection) {
    this.connection = connection;
  }

  static SqliteSession open(Path file, Duration busyTimeout) {
    Objects.requireNonNull(file, "file");
    try {
      Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
      try (Statement s = c.createStatement()) {
        s.execute("PRAGMA busy_timeout = " + busyTimeout.toMillis());
        s.execute("PRAGMA journal_mode = WAL");
        s.execute("PRAGMA synchronous = FULL");
      }
      SqliteSession session = new SqliteSession(c);
      session.migrate();
      return session;
    } catch (SQLException e) {
      throw new StorageException("cannot open " + file, e);
    }
  }

  private void migrate() {
    inTransaction(
        c -> {
          try (Statement s = c.createStatement()) {
            int version;
            try (var rs = s.executeQuery("PRAGMA user_version")) {
              version = rs.next() ? rs.getInt(1) : 0;
            }
            if (version > SCHEMA_VERSION) {
              throw new StorageException(
                  "schema version " + version + " is newer than supported " + SCHEMA_VERSION);
            }
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS events (
                  global_position INTEGER PRIMARY KEY AUTOINCREMENT,
                  stream_id TEXT NOT NULL,
                  version INTEGER NOT NULL CHECK (version >= 1),
                  event_id TEXT NOT NULL,
                  causation_id TEXT NOT NULL,
                  type TEXT NOT NULL CHECK (type IN ('AccountOpened', 'MoneyDeposited',
                    'MoneyWithdrawn', 'AccountFrozen', 'AccountUnfrozen', 'AccountClosed')),
                  account_id TEXT NOT NULL,
                  occurred_at TEXT NOT NULL,
                  owner TEXT,
                  currency TEXT,
                  overdraft_limit_minor INTEGER,
                  amount_minor INTEGER,
                  reference TEXT,
                  reason TEXT,
                  UNIQUE (stream_id, version)
                )""");
            s.execute("PRAGMA user_version = " + SCHEMA_VERSION);
          }
          return Boolean.TRUE;
        });
  }

  /** Ejecuta {@code work} en una transacción de escritura; si lanza, hace rollback y relanza. */
  <T> T inTransaction(Work<T> work) {
    synchronized (lock) {
      try {
        execute("BEGIN IMMEDIATE");
      } catch (SQLException e) {
        throw new StorageException("cannot begin transaction", e);
      }
      T result;
      try {
        result = work.run(connection);
      } catch (SQLException e) {
        rollbackQuietly(e);
        throw new StorageException("transaction failed", e);
      } catch (RuntimeException | Error e) {
        rollbackQuietly(e);
        throw e;
      }
      try {
        execute("COMMIT");
      } catch (SQLException e) {
        rollbackQuietly(e);
        throw new StorageException("commit failed", e);
      }
      return result;
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement s = connection.createStatement()) {
      s.execute(sql);
    }
  }

  private void rollbackQuietly(Throwable original) {
    try {
      execute("ROLLBACK");
    } catch (SQLException e) {
      original.addSuppressed(e);
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      try {
        connection.close();
      } catch (SQLException e) {
        throw new StorageException("cannot close", e);
      }
    }
  }
}
