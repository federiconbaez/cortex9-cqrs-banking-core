package io.github.federiconbaez.banking.support;

import io.github.federiconbaez.banking.adapter.inmemory.FixedClock;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryEventStore;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryIdempotencyStore;
import io.github.federiconbaez.banking.adapter.inmemory.SequentialIdGenerator;
import io.github.federiconbaez.banking.application.AccountRepository;
import io.github.federiconbaez.banking.application.Command;
import io.github.federiconbaez.banking.application.CommandBus;
import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.CommandResult;
import io.github.federiconbaez.banking.application.port.EventStore;
import io.github.federiconbaez.banking.application.port.IdempotencyStore;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.Money;
import java.util.concurrent.atomic.AtomicInteger;

/** Composicion en memoria para tests (wiring manual, sin framework). */
public final class Bank {
  public final EventStore store;
  public final IdempotencyStore idempotency;
  public final FixedClock clock = new FixedClock(Fixtures.T0);
  public final CommandBus bus;
  private final AtomicInteger seq = new AtomicInteger();

  public Bank() {
    this(new InMemoryEventStore(), new InMemoryIdempotencyStore(), CommandBus.DEFAULT_MAX_ATTEMPTS);
  }

  public Bank(EventStore store, IdempotencyStore idempotency, int maxAttempts) {
    this.store = store;
    this.idempotency = idempotency;
    this.bus =
        new CommandBus(store, idempotency, clock, new SequentialIdGenerator("evt"), maxAttempts);
  }

  public CommandId nextId() {
    return new CommandId("cmd-" + seq.incrementAndGet());
  }

  public CommandResult open(String id, Currency currency, long overdraft) {
    return bus.dispatch(
        new Command.OpenAccount(nextId(), new AccountId(id), "owner-" + id, currency, overdraft));
  }

  public CommandResult deposit(String id, long minor, Currency currency) {
    return bus.dispatch(
        new Command.Deposit(nextId(), new AccountId(id), Money.of(minor, currency), "seed"));
  }

  public long balance(String id) {
    return new AccountRepository(store, () -> "unused")
        .load(new AccountId(id))
        .account()
        .balanceMinor();
  }
}
