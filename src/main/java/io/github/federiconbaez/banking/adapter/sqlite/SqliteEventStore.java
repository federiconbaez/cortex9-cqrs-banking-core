package io.github.federiconbaez.banking.adapter.sqlite;

import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.port.AppendResult;
import io.github.federiconbaez.banking.application.port.EventStore;
import io.github.federiconbaez.banking.application.port.NewEvent;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Event Store durable en un archivo SQLite. Cumple {@code EventStoreContract} igual que el adapter
 * en memoria.
 *
 * <ul>
 *   <li>{@code append} corre en una transacción {@code BEGIN IMMEDIATE}: lee la versión actual del
 *       stream, compara con la esperada y escribe todos los eventos o ninguno;
 *   <li>{@code UNIQUE(stream_id, version)} es la segunda línea de defensa: aunque alguien escriba
 *       por fuera del adapter, la base no admite dos eventos con la misma versión;
 *   <li>{@code global_position} es {@code AUTOINCREMENT}: estrictamente creciente y sin reutilizar
 *       posiciones. Un rollback no deja huecos, porque el contador también vuelve atrás.
 * </ul>
 */
public final class SqliteEventStore implements EventStore, AutoCloseable {
  private static final String SELECT =
      "SELECT global_position, stream_id, version, event_id, causation_id, type, account_id,"
          + " occurred_at, owner, currency, overdraft_limit_minor, amount_minor, reference, reason"
          + " FROM events";

  private final SqliteSession session;

  private SqliteEventStore(SqliteSession session) {
    this.session = session;
  }

  /** Abre (o crea) el archivo y aplica el esquema. */
  public static SqliteEventStore open(Path file) {
    return new SqliteEventStore(SqliteSession.open(file, Duration.ofSeconds(10)));
  }

  @Override
  public List<StoredEvent> load(String streamId) {
    Objects.requireNonNull(streamId, "streamId");
    return session.inTransaction(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(SELECT + " WHERE stream_id = ? ORDER BY version")) {
            ps.setString(1, streamId);
            return readList(ps);
          }
        });
  }

  @Override
  public AppendResult append(
      String streamId, long expectedVersion, CommandId causationId, List<NewEvent> events) {
    Objects.requireNonNull(streamId, "streamId");
    Objects.requireNonNull(causationId, "causationId");
    if (events.isEmpty()) {
      throw new IllegalArgumentException("events must not be empty");
    }
    return session.inTransaction(
        c -> {
          long actual = currentVersion(c, streamId);
          if (actual != expectedVersion) {
            return new AppendResult.VersionConflict(expectedVersion, actual);
          }
          List<StoredEvent> stored = new ArrayList<>(events.size());
          long version = actual;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO events (stream_id, version, event_id, causation_id, "
                      + EventRows.BODY_COLUMNS
                      + ") VALUES (?, ?, ?, ?, "
                      + EventRows.BODY_PLACEHOLDERS
                      + ")",
                  java.sql.Statement.RETURN_GENERATED_KEYS)) {
            for (NewEvent event : events) {
              version++;
              ps.setString(1, streamId);
              ps.setLong(2, version);
              ps.setString(3, event.eventId());
              ps.setString(4, causationId.value());
              EventRows.bindBody(ps, 5, event.event());
              ps.executeUpdate();
              long position;
              try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                  throw new StorageException("no global position returned");
                }
                position = keys.getLong(1);
              }
              stored.add(
                  new StoredEvent(
                      event.eventId(), streamId, version, position, causationId, event.event()));
            }
          }
          return new AppendResult.Appended(stored);
        });
  }

  @Override
  public List<StoredEvent> readAll(long afterGlobalPosition, int maxCount) {
    if (maxCount < 1) {
      throw new IllegalArgumentException("maxCount must be >= 1");
    }
    return session.inTransaction(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  SELECT + " WHERE global_position > ? ORDER BY global_position LIMIT ?")) {
            ps.setLong(1, afterGlobalPosition);
            ps.setInt(2, maxCount);
            return readList(ps);
          }
        });
  }

  private static long currentVersion(Connection c, String streamId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT COALESCE(MAX(version), 0) FROM events WHERE stream_id = ?")) {
      ps.setString(1, streamId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static List<StoredEvent> readList(PreparedStatement ps) throws SQLException {
    List<StoredEvent> out = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(
            new StoredEvent(
                rs.getString("event_id"),
                rs.getString("stream_id"),
                rs.getLong("version"),
                rs.getLong("global_position"),
                new CommandId(rs.getString("causation_id")),
                EventRows.read(rs)));
      }
    }
    return List.copyOf(out);
  }

  @Override
  public void close() {
    session.close();
  }
}
