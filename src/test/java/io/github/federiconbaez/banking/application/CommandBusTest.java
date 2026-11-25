package io.github.federiconbaez.banking.application;

import static io.github.federiconbaez.banking.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.federiconbaez.banking.adapter.inmemory.FixedClock;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryEventStore;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryIdempotencyStore;
import io.github.federiconbaez.banking.adapter.inmemory.SequentialIdGenerator;
import io.github.federiconbaez.banking.application.CommandResult.Accepted;
import io.github.federiconbaez.banking.application.CommandResult.ConcurrencyConflict;
import io.github.federiconbaez.banking.application.CommandResult.IdempotencyKeyReused;
import io.github.federiconbaez.banking.application.CommandResult.Rejected;
import io.github.federiconbaez.banking.domain.AccountEvent;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.AccountStatus;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.DomainError;
import io.github.federiconbaez.banking.domain.Money;
import io.github.federiconbaez.banking.support.Bank;
import io.github.federiconbaez.banking.support.ConflictInjectingEventStore;
import io.github.federiconbaez.banking.support.ForgetfulIdempotencyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class CommandBusTest {

  static final AccountId A = new AccountId("A");

  static Money ars(long m) {
    return Money.of(m, Currency.ARS);
  }

  @Test
  void acceptedCommandsAdvanceStreamVersion() {
    Bank bank = new Bank();
    CommandResult opened = bank.open("A", Currency.ARS, 0);
    assertThat(opened).isInstanceOf(Accepted.class);
    assertThat(((Accepted) opened).version()).isEqualTo(1);

    bank.clock.advance(Duration.ofMinutes(1));
    Accepted deposit =
        (Accepted) bank.bus.dispatch(new Command.Deposit(bank.nextId(), A, ars(100), "r"));
    assertThat(deposit.version()).isEqualTo(2);
    assertThat(deposit.accountId()).isEqualTo(A);
    assertThat(deposit.events())
        .containsExactly(
            new AccountEvent.MoneyDeposited(A, ars(100), "r", T0.plus(Duration.ofMinutes(1))));
    assertThat(bank.store.load("account-A"))
        .extracting(e -> e.eventId())
        .containsExactly("evt-1", "evt-2");
  }

  @Test
  void allCommandKindsAreRouted() {
    Bank bank = new Bank();
    bank.open("A", Currency.ARS, 0);
    assertThat(bank.bus.dispatch(new Command.FreezeAccount(bank.nextId(), A, "kyc")))
        .isInstanceOf(Accepted.class);
    assertThat(bank.bus.dispatch(new Command.Withdraw(bank.nextId(), A, ars(1), "w")))
        .isEqualTo(new Rejected(new DomainError.AccountIsFrozen(A)));
    assertThat(bank.bus.dispatch(new Command.UnfreezeAccount(bank.nextId(), A)))
        .isInstanceOf(Accepted.class);
    assertThat(bank.bus.dispatch(new Command.CloseAccount(bank.nextId(), A)))
        .isInstanceOf(Accepted.class);
    assertThat(new AccountRepository(bank.store, () -> "x").load(A).account().status())
        .isEqualTo(AccountStatus.CLOSED);
  }

  @Test
  void sameCommandTwiceReturnsSameResultWithoutNewEvents() {
    Bank bank = new Bank();
    bank.open("A", Currency.ARS, 0);
    Command deposit = new Command.Deposit(new CommandId("dep-1"), A, ars(100), "r");

    CommandResult first = bank.bus.dispatch(deposit);
    bank.clock.advance(Duration.ofHours(1));
    CommandResult second = bank.bus.dispatch(deposit);

    assertThat(second).isEqualTo(first);
    assertThat(bank.store.load("account-A")).hasSize(2);
    assertThat(bank.balance("A")).isEqualTo(100);
  }

  @Test
  void rejectionsAreCachedToo() {
    Bank bank = new Bank();
    bank.open("A", Currency.ARS, 0);
    Command withdraw = new Command.Withdraw(new CommandId("w-1"), A, ars(50), "w");
    CommandResult first = bank.bus.dispatch(withdraw);
    assertThat(first).isEqualTo(new Rejected(new DomainError.InsufficientFunds(0, 50)));

    bank.deposit("A", 100, Currency.ARS);

    assertThat(bank.bus.dispatch(withdraw)).isEqualTo(first);
    assertThat(bank.balance("A")).isEqualTo(100);
  }

  @Test
  void reusedKeyWithDifferentPayloadIsReported() {
    Bank bank = new Bank();
    bank.open("A", Currency.ARS, 0);
    CommandId key = new CommandId("k");
    bank.bus.dispatch(new Command.Deposit(key, A, ars(1), "r"));
    assertThat(bank.bus.dispatch(new Command.Deposit(key, A, ars(2), "r")))
        .isEqualTo(new IdempotencyKeyReused(key));
    assertThat(bank.balance("A")).isEqualTo(1);
  }

  @Test
  void retriesOnVersionConflictWithinBudget() {
    ConflictInjectingEventStore store =
        new ConflictInjectingEventStore(new InMemoryEventStore(), 0);
    Bank bank = new Bank(store, new InMemoryIdempotencyStore(), 3);
    bank.open("A", Currency.ARS, 0);
    store.injectConflicts(2);
    store.appendCalls = 0;

    assertThat(bank.deposit("A", 10, Currency.ARS)).isInstanceOf(Accepted.class);
    assertThat(store.appendCalls).isEqualTo(3);
    assertThat(bank.balance("A")).isEqualTo(10);
  }

  @Test
  void exhaustedRetriesReturnConflictAndAreNotCached() {
    ConflictInjectingEventStore store =
        new ConflictInjectingEventStore(new InMemoryEventStore(), 0);
    Bank bank = new Bank(store, new InMemoryIdempotencyStore(), 2);
    bank.open("A", Currency.ARS, 0);
    store.injectConflicts(2);
    Command deposit = new Command.Deposit(new CommandId("d"), A, ars(10), "r");

    assertThat(bank.bus.dispatch(deposit)).isEqualTo(new ConcurrencyConflict(2));
    assertThat(bank.idempotency.find(new CommandId("d"))).isEmpty();
    assertThat(bank.bus.dispatch(deposit)).isInstanceOf(Accepted.class);
  }

  @Test
  void causationIdDedupesEvenIfIdempotencyStoreLostTheRecord() {
    Bank bank = new Bank(new InMemoryEventStore(), new ForgetfulIdempotencyStore(), 3);
    bank.open("A", Currency.ARS, 0);
    Command deposit = new Command.Deposit(new CommandId("d"), A, ars(10), "r");

    CommandResult first = bank.bus.dispatch(deposit);
    CommandResult replay = bank.bus.dispatch(deposit);

    assertThat(replay).isEqualTo(first);
    assertThat(((Accepted) replay).version()).isEqualTo(2);
    assertThat(bank.balance("A")).isEqualTo(10);
  }

  @Test
  void rejectsInvalidRetryBudget() {
    assertThatThrownBy(
            () ->
                new CommandBus(
                    new InMemoryEventStore(),
                    new InMemoryIdempotencyStore(),
                    new FixedClock(T0),
                    new SequentialIdGenerator("e"),
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void concurrentDepositsAreSerializedByOptimisticConcurrency() throws Exception {
    Bank bank = new Bank(new InMemoryEventStore(), new InMemoryIdempotencyStore(), 1_000);
    bank.open("A", Currency.ARS, 0);
    int threads = 8;
    int perThread = 50;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int thread = t;
        futures.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < perThread; i++) {
                    CommandResult r =
                        bank.bus.dispatch(
                            new Command.Deposit(
                                new CommandId("t" + thread + "-" + i), A, ars(1), "c"));
                    assertThat(r).isInstanceOf(Accepted.class);
                  }
                }));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdown();
    }
    assertThat(bank.balance("A")).isEqualTo((long) threads * perThread);
    assertThat(bank.store.load("account-A")).hasSize(1 + threads * perThread);
  }
}
