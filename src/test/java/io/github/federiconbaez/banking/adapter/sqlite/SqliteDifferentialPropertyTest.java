package io.github.federiconbaez.banking.adapter.sqlite;

import static io.github.federiconbaez.banking.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.federiconbaez.banking.adapter.inmemory.FixedClock;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryEventStore;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryIdempotencyStore;
import io.github.federiconbaez.banking.adapter.inmemory.SequentialIdGenerator;
import io.github.federiconbaez.banking.application.Command;
import io.github.federiconbaez.banking.application.CommandBus;
import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.CommandResult;
import io.github.federiconbaez.banking.application.port.EventStore;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.Money;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

/**
 * Prueba diferencial: la misma secuencia aleatoria de comandos (con reintentos de ids ya usados)
 * contra el bus sobre SQLite y sobre el fake en memoria da los mismos resultados y el mismo log
 * global, evento por evento. Después de reabrir el archivo, el log sigue igual.
 */
class SqliteDifferentialPropertyTest {
  private static final AtomicInteger FILES = new AtomicInteger();
  private static Path dir;

  @BeforeContainer
  static void createDir() throws IOException {
    dir = Files.createTempDirectory("bank-differential");
  }

  @AfterContainer
  static void deleteDir() throws IOException {
    try (var files = Files.list(dir)) {
      for (Path p : files.toList()) {
        Files.delete(p);
      }
    }
    Files.delete(dir);
  }

  private record Step(int kind, int account, long amount, int commandId) {}

  @Property(tries = 100)
  void sqliteAndInMemoryAgreeOnEveryResultAndEvent(@ForAll("scripts") List<Step> script) {
    Path file = dir.resolve("diff-" + FILES.incrementAndGet() + ".db");
    InMemoryEventStore memory = new InMemoryEventStore();
    List<CommandResult> expected = run(memory, script);
    List<CommandResult> actual;
    try (SqliteEventStore sqlite = SqliteEventStore.open(file)) {
      actual = run(sqlite, script);
      assertThat(sqlite.readAll(0, 10_000)).isEqualTo(memory.readAll(0, 10_000));
    }
    assertThat(actual).isEqualTo(expected);
    try (SqliteEventStore reopened = SqliteEventStore.open(file)) {
      assertThat(reopened.readAll(0, 10_000)).isEqualTo(memory.readAll(0, 10_000));
    }
  }

  private static List<CommandResult> run(EventStore store, List<Step> script) {
    FixedClock clock = new FixedClock(T0);
    CommandBus bus =
        new CommandBus(
            store,
            new InMemoryIdempotencyStore(),
            clock,
            new SequentialIdGenerator("evt"),
            CommandBus.DEFAULT_MAX_ATTEMPTS);
    List<CommandResult> results = new ArrayList<>();
    for (Step s : script) {
      clock.advance(Duration.ofMillis(1));
      results.add(bus.dispatch(command(s)));
    }
    return results;
  }

  private static Command command(Step s) {
    CommandId id = new CommandId("cmd-" + s.commandId());
    AccountId acc = new AccountId("acc-" + s.account());
    Money money = Money.of(s.amount(), Currency.ARS);
    return switch (s.kind()) {
      case 0 -> new Command.OpenAccount(id, acc, "owner-" + s.account(), Currency.ARS, s.amount());
      case 1 -> new Command.Deposit(id, acc, money, "dep");
      case 2 -> new Command.Withdraw(id, acc, money, "wd");
      case 3 -> new Command.FreezeAccount(id, acc, "check");
      case 4 -> new Command.UnfreezeAccount(id, acc);
      default -> new Command.CloseAccount(id, acc);
    };
  }

  @Provide
  Arbitrary<List<Step>> scripts() {
    Arbitrary<Step> step =
        Combinators.combine(
                Arbitraries.integers().between(0, 5),
                Arbitraries.integers().between(0, 2),
                Arbitraries.longs().between(0, 2_000),
                Arbitraries.integers().between(0, 25))
            .as(Step::new);
    return step.list().ofMinSize(1).ofMaxSize(40);
  }
}
