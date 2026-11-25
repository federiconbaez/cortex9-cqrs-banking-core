package io.github.federiconbaez.banking.application;

import io.github.federiconbaez.banking.application.Command.CloseAccount;
import io.github.federiconbaez.banking.application.Command.Deposit;
import io.github.federiconbaez.banking.application.Command.FreezeAccount;
import io.github.federiconbaez.banking.application.Command.OpenAccount;
import io.github.federiconbaez.banking.application.Command.UnfreezeAccount;
import io.github.federiconbaez.banking.application.Command.Withdraw;
import io.github.federiconbaez.banking.application.CommandResult.Accepted;
import io.github.federiconbaez.banking.application.CommandResult.ConcurrencyConflict;
import io.github.federiconbaez.banking.application.CommandResult.IdempotencyKeyReused;
import io.github.federiconbaez.banking.application.CommandResult.Rejected;
import io.github.federiconbaez.banking.application.port.AppendResult;
import io.github.federiconbaez.banking.application.port.Clock;
import io.github.federiconbaez.banking.application.port.CommandGateway;
import io.github.federiconbaez.banking.application.port.EventStore;
import io.github.federiconbaez.banking.application.port.IdGenerator;
import io.github.federiconbaez.banking.application.port.IdempotencyRecord;
import io.github.federiconbaez.banking.application.port.IdempotencyStore;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import io.github.federiconbaez.banking.domain.Account;
import io.github.federiconbaez.banking.domain.AccountEvent;
import io.github.federiconbaez.banking.domain.Result;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lado Command de CQRS. Por comando: dedupe (store de idempotencia y, como respaldo, causationId en
 * el stream) -&gt; load -&gt; decide -&gt; append con expected version -&gt; reintento acotado ante
 * conflicto.
 */
public final class CommandBus implements CommandGateway {

  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  private final AccountRepository repository;
  private final IdempotencyStore idempotency;
  private final Clock clock;
  private final int maxAttempts;

  public CommandBus(
      EventStore store,
      IdempotencyStore idempotency,
      Clock clock,
      IdGenerator ids,
      int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    this.repository = new AccountRepository(store, ids);
    this.idempotency = idempotency;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
  }

  @Override
  public CommandResult dispatch(Command command) {
    Optional<IdempotencyRecord> prior = idempotency.find(command.commandId());
    if (prior.isPresent()) {
      return prior.get().command().equals(command)
          ? prior.get().result()
          : new IdempotencyKeyReused(command.commandId());
    }
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      AccountRepository.Loaded loaded = repository.load(command.accountId());
      Optional<Accepted> applied = alreadyApplied(command, loaded.history());
      if (applied.isPresent()) {
        return remember(command, applied.get());
      }
      Account account = loaded.account();
      Result<AccountEvent> decision = decide(account, command, clock.now());
      if (decision instanceof Result.Err<AccountEvent> err) {
        return remember(command, new Rejected(err.error()));
      }
      if (repository.save(account, command.commandId()) instanceof AppendResult.Appended appended) {
        return remember(command, accepted(command, appended.events()));
      }
    }
    return new ConcurrencyConflict(maxAttempts);
  }

  private CommandResult remember(Command command, CommandResult result) {
    idempotency.save(command.commandId(), new IdempotencyRecord(command, result));
    return result;
  }

  private static Optional<Accepted> alreadyApplied(Command command, List<StoredEvent> history) {
    List<StoredEvent> caused =
        history.stream().filter(e -> e.causationId().equals(command.commandId())).toList();
    if (caused.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(accepted(command, caused));
  }

  private static Accepted accepted(Command command, List<StoredEvent> events) {
    long version = events.get(events.size() - 1).version();
    return new Accepted(
        command.accountId(), version, events.stream().map(StoredEvent::event).toList());
  }

  private static Result<AccountEvent> decide(Account account, Command command, Instant now) {
    return switch (command) {
      case OpenAccount c -> account.open(c.owner(), c.currency(), c.overdraftLimitMinor(), now);
      case Deposit c -> account.deposit(c.amount(), c.reference(), now);
      case Withdraw c -> account.withdraw(c.amount(), c.reference(), now);
      case FreezeAccount c -> account.freeze(c.reason(), now);
      case UnfreezeAccount c -> account.unfreeze(now);
      case CloseAccount c -> account.close(now);
    };
  }
}
