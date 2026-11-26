package io.github.federiconbaez.banking.adapter.sqlite;

import io.github.federiconbaez.banking.adapter.contract.EventStoreContract;
import io.github.federiconbaez.banking.application.port.EventStore;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

class SqliteEventStoreContractTest extends EventStoreContract {
  @TempDir Path dir;
  private SqliteEventStore sqlite;

  @Override
  protected EventStore newStore() {
    sqlite = SqliteEventStore.open(dir.resolve("events.db"));
    return sqlite;
  }

  @AfterEach
  void close() {
    sqlite.close();
  }
}
