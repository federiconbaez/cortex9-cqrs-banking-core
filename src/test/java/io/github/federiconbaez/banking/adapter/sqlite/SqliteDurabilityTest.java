package io.github.federiconbaez.banking.adapter.sqlite;

import static io.github.federiconbaez.banking.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.federiconbaez.banking.adapter.inmemory.FixedClock;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryBalanceReadModel;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryIdempotencyStore;
import io.github.federiconbaez.banking.adapter.inmemory.SequentialIdGenerator;
import io.github.federiconbaez.banking.application.Command;
import io.github.federiconbaez.banking.application.CommandBus;
import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.CommandResult;
import io.github.federiconbaez.banking.application.port.AppendResult;
import io.github.federiconbaez.banking.application.port.NewEvent;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import io.github.federiconbaez.banking.application.projection.ProjectionRunner;
import io.github.federiconbaez.banking.domain.AccountEvent;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.Money;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lo que el fake en memoria no puede probar: persistencia, reinicios y varias conexiones. */
class SqliteDurabilityTest {
  private static final AccountId ACC = new AccountId("acc-1");

  @TempDir Path dir;

  private static CommandBus bus(SqliteEventStore store) {
    return new CommandBus(
        store,
        new InMemoryIdempotencyStore(),
        new FixedClock(T0),
        new SequentialIdGenerator("evt"),
        CommandBus.DEFAULT_MAX_ATTEMPTS);
  }

  private static NewEvent deposit(String id) {
    return new NewEvent(
        id, new AccountEvent.MoneyDeposited(ACC, Money.of(1, Currency.ARS), "r", T0));
  }

  @Test
  void eventsSurviveCloseAndReopen() {
    Path file = dir.resolve("bank.db");
    try (SqliteEventStore store = SqliteEventStore.open(file)) {
      CommandBus bus = bus(store);
      bus.dispatch(new Command.OpenAccount(new CommandId("c1"), ACC, "ana", Currency.ARS, 0));
      bus.dispatch(
          new Command.Deposit(new CommandId("c2"), ACC, Money.of(1_500, Currency.ARS), "sueldo"));
    }
    try (SqliteEventStore store = SqliteEventStore.open(file)) {
      assertThat(store.load("account-acc-1"))
          .extracting(StoredEvent::version)
          .containsExactly(1L, 2L);
      CommandResult r =
          bus(store)
              .dispatch(
                  new Command.Withdraw(
                      new CommandId("c3"), ACC, Money.of(500, Currency.ARS), "cajero"));
      assertThat(r).isInstanceOf(CommandResult.Accepted.class);
      assertThat(((CommandResult.Accepted) r).version()).isEqualTo(3);
    }
  }

  @Test
  void afterARestartThatLosesTheIdempotencyStoreARetryDoesNotDuplicateTheDeposit() {
    Path file = dir.resolve("bank.db");
    Command.Deposit deposit =
        new Command.Deposit(new CommandId("dep-1"), ACC, Money.of(700, Currency.ARS), "x");
    try (SqliteEventStore store = SqliteEventStore.open(file)) {
      CommandBus bus = bus(store);
      bus.dispatch(new Command.OpenAccount(new CommandId("open"), ACC, "ana", Currency.ARS, 0));
      bus.dispatch(deposit);
    }
    // El proceso muere: el store de idempotencia (en memoria) se pierde, el log queda en disco.
    try (SqliteEventStore store = SqliteEventStore.open(file)) {
      CommandResult retry = bus(store).dispatch(deposit);
      assertThat(retry).isInstanceOf(CommandResult.Accepted.class);
      assertThat(((CommandResult.Accepted) retry).version()).isEqualTo(2);
      assertThat(store.load("account-acc-1")).hasSize(2);

      InMemoryBalanceReadModel balances = new InMemoryBalanceReadModel();
      new ProjectionRunner(store, List.of(balances), 1).catchUp();
      assertThat(balances.balanceOf(ACC)).contains(Money.of(700, Currency.ARS));
    }
  }

  @Test
  void twoConnectionsAppendingAtTheSameVersionHaveExactlyOneWinner() throws Exception {
    Path file = dir.resolve("shared.db");
    try (SqliteEventStore p1 = SqliteEventStore.open(file);
        SqliteEventStore p2 = SqliteEventStore.open(file)) {
      int rounds = 50;
      for (int round = 0; round < rounds; round++) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AppendResult>> results = new ArrayList<>();
        long expected = round;
        for (int t = 0; t < 4; t++) {
          SqliteEventStore store = t % 2 == 0 ? p1 : p2;
          String id = "r" + round + "-w" + t;
          results.add(
              pool.submit(
                  () -> {
                    start.await();
                    return store.append("hot", expected, new CommandId(id), List.of(deposit(id)));
                  }));
        }
        start.countDown();
        int winners = 0;
        for (Future<AppendResult> f : results) {
          if (f.get(30, TimeUnit.SECONDS) instanceof AppendResult.Appended) {
            winners++;
          }
        }
        pool.shutdown();
        assertThat(winners).as("round %d", round).isEqualTo(1);
      }
      assertThat(p1.load("hot"))
          .extracting(StoredEvent::version)
          .hasSize(rounds)
          .doesNotHaveDuplicates();
      assertThat(p2.readAll(0, 1_000))
          .extracting(StoredEvent::globalPosition)
          .isSorted()
          .doesNotHaveDuplicates()
          .hasSize(rounds);
    }
  }

  @Test
  void theDatabaseRejectsADuplicateVersionEvenWhenWrittenAroundTheAdapter() throws Exception {
    Path file = dir.resolve("unique.db");
    try (SqliteEventStore store = SqliteEventStore.open(file)) {
      store.append("s", 0, new CommandId("c"), List.of(deposit("e1")));
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "INSERT INTO events (stream_id, version, event_id, causation_id, type,"
                          + " account_id, occurred_at) VALUES ('s', 1, 'dup', 'c',"
                          + " 'AccountClosed', 'acc-1', '2026-01-01T00:00:00Z')"))
          .hasMessageContaining("UNIQUE");
    }
  }

  @Test
  void corruptRowIsAStorageErrorAndTheConnectionStaysUsable() throws Exception {
    Path file = dir.resolve("corrupt.db");
    try (SqliteEventStore store = SqliteEventStore.open(file)) {
      store.append("s", 0, new CommandId("c"), List.of(deposit("e1")));
      store.append("t", 0, new CommandId("c"), List.of(deposit("e2")));
      store.append(
          "o",
          0,
          new CommandId("c"),
          List.of(
              new NewEvent("e0", new AccountEvent.AccountOpened(ACC, "ana", Currency.ARS, 0, T0))));
      try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
          Statement s = c.createStatement()) {
        s.execute("UPDATE events SET owner = NULL WHERE stream_id = 'o'");
        s.execute("UPDATE events SET amount_minor = NULL WHERE stream_id = 's'");
        s.execute("UPDATE events SET currency = 'XXX' WHERE stream_id = 't'");
      }
      assertThatThrownBy(() -> store.load("s"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("amount_minor");
      assertThatThrownBy(() -> store.load("t"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("XXX");
      assertThatThrownBy(() -> store.load("o"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("owner");
      assertThat(store.append("u", 0, new CommandId("c"), List.of(deposit("e3"))))
          .isInstanceOf(AppendResult.Appended.class);
    }
  }

  @Test
  void sqlFailureInsideAnAppendRollsBackTheWholeBatch() throws Exception {
    Path file = dir.resolve("trigger.db");
    try (SqliteEventStore store = SqliteEventStore.open(file)) {
      try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
          Statement s = c.createStatement()) {
        s.execute(
            "CREATE TRIGGER no_e2 BEFORE INSERT ON events WHEN NEW.event_id = 'e2'"
                + " BEGIN SELECT RAISE(ABORT, 'boom'); END");
      }
      assertThatThrownBy(
              () -> store.append("s", 0, new CommandId("c"), List.of(deposit("e1"), deposit("e2"))))
          .isInstanceOf(StorageException.class);
      assertThat(store.load("s")).isEmpty();
      assertThat(store.readAll(0, 10)).isEmpty();
    }
  }

  @Test
  void closedStoreRejectsFurtherWork() {
    SqliteEventStore store = SqliteEventStore.open(dir.resolve("closed.db"));
    store.close();
    assertThatThrownBy(() -> store.load("s")).isInstanceOf(StorageException.class);
  }

  @Test
  void refusesASchemaNewerThanItKnows() throws Exception {
    Path file = dir.resolve("future.db");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement s = c.createStatement()) {
      s.execute("PRAGMA user_version = 99");
    }
    assertThatThrownBy(() -> SqliteEventStore.open(file))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("99");
  }

  @Test
  void unreadableFileIsAStorageError() throws Exception {
    Path file = dir.resolve("garbage.db");
    Files.writeString(file, "not a database ".repeat(100));
    assertThatThrownBy(() -> SqliteEventStore.open(file)).isInstanceOf(StorageException.class);
  }
}
