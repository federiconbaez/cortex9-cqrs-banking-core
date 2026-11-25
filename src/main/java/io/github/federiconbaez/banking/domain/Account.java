package io.github.federiconbaez.banking.domain;

import io.github.federiconbaez.banking.domain.AccountEvent.AccountClosed;
import io.github.federiconbaez.banking.domain.AccountEvent.AccountFrozen;
import io.github.federiconbaez.banking.domain.AccountEvent.AccountOpened;
import io.github.federiconbaez.banking.domain.AccountEvent.AccountUnfrozen;
import io.github.federiconbaez.banking.domain.AccountEvent.MoneyDeposited;
import io.github.federiconbaez.banking.domain.AccountEvent.MoneyWithdrawn;
import io.github.federiconbaez.banking.domain.DomainError.AccountAlreadyOpened;
import io.github.federiconbaez.banking.domain.DomainError.AccountIsClosed;
import io.github.federiconbaez.banking.domain.DomainError.AccountIsFrozen;
import io.github.federiconbaez.banking.domain.DomainError.AccountNotFrozen;
import io.github.federiconbaez.banking.domain.DomainError.AccountNotOpened;
import io.github.federiconbaez.banking.domain.DomainError.BalanceOverflow;
import io.github.federiconbaez.banking.domain.DomainError.CurrencyMismatch;
import io.github.federiconbaez.banking.domain.DomainError.InsufficientFunds;
import io.github.federiconbaez.banking.domain.DomainError.InvalidAmount;
import io.github.federiconbaez.banking.domain.DomainError.InvalidOverdraftLimit;
import io.github.federiconbaez.banking.domain.DomainError.InvalidOwner;
import io.github.federiconbaez.banking.domain.DomainError.NonZeroBalance;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Agregado Account. Invariantes:
 *
 * <ul>
 *   <li>saldo &gt;= -limiteDescubierto (por defecto 0: nunca negativo);
 *   <li>una sola moneda por cuenta, fijada al abrir;
 *   <li>congelada: admite creditos, rechaza debitos; cerrada: estado terminal, sin movimientos;
 *   <li>solo se cierra con saldo exactamente 0.
 * </ul>
 */
public final class Account extends AggregateRoot<AccountEvent> {

  private final AccountId id;
  private AccountStatus status = AccountStatus.NOT_OPENED;
  private @Nullable Currency currency;
  private @Nullable String owner;
  private long balanceMinor;
  private long overdraftLimitMinor;

  private Account(AccountId id) {
    this.id = Objects.requireNonNull(id, "id");
  }

  public static Account fresh(AccountId id) {
    return new Account(id);
  }

  public static Account rehydrate(AccountId id, List<? extends AccountEvent> history) {
    Account account = new Account(id);
    account.replay(history);
    return account;
  }

  public Result<AccountEvent> open(
      String owner, Currency currency, long overdraftLimitMinor, Instant now) {
    if (status != AccountStatus.NOT_OPENED) {
      return Result.err(new AccountAlreadyOpened(id));
    }
    if (owner.isBlank()) {
      return Result.err(new InvalidOwner(owner));
    }
    if (overdraftLimitMinor < 0) {
      return Result.err(new InvalidOverdraftLimit(overdraftLimitMinor));
    }
    return Result.ok(raise(new AccountOpened(id, owner, currency, overdraftLimitMinor, now)));
  }

  public Result<AccountEvent> deposit(Money amount, String reference, Instant now) {
    DomainError error = usabilityError();
    if (error == null) {
      error = amountError(amount);
    }
    if (error == null && balanceMinor > Long.MAX_VALUE - amount.minorUnits()) {
      error = new BalanceOverflow(balanceMinor, amount.minorUnits());
    }
    if (error != null) {
      return Result.err(error);
    }
    return Result.ok(raise(new MoneyDeposited(id, amount, reference, now)));
  }

  public Result<AccountEvent> withdraw(Money amount, String reference, Instant now) {
    DomainError error = usabilityError();
    if (error == null && status == AccountStatus.FROZEN) {
      error = new AccountIsFrozen(id);
    }
    if (error == null) {
      error = amountError(amount);
    }
    if (error == null) {
      long available = availableMinor();
      if (amount.minorUnits() > available) {
        error = new InsufficientFunds(available, amount.minorUnits());
      }
    }
    if (error != null) {
      return Result.err(error);
    }
    return Result.ok(raise(new MoneyWithdrawn(id, amount, reference, now)));
  }

  public Result<AccountEvent> freeze(String reason, Instant now) {
    DomainError error = usabilityError();
    if (error == null && status == AccountStatus.FROZEN) {
      error = new AccountIsFrozen(id);
    }
    if (error != null) {
      return Result.err(error);
    }
    return Result.ok(raise(new AccountFrozen(id, reason, now)));
  }

  public Result<AccountEvent> unfreeze(Instant now) {
    DomainError error = usabilityError();
    if (error == null && status != AccountStatus.FROZEN) {
      error = new AccountNotFrozen(id);
    }
    if (error != null) {
      return Result.err(error);
    }
    return Result.ok(raise(new AccountUnfrozen(id, now)));
  }

  public Result<AccountEvent> close(Instant now) {
    DomainError error = usabilityError();
    if (error == null && balanceMinor != 0) {
      error = new NonZeroBalance(balanceMinor);
    }
    if (error != null) {
      return Result.err(error);
    }
    return Result.ok(raise(new AccountClosed(id, now)));
  }

  @Override
  protected void when(AccountEvent event) {
    switch (event) {
      case AccountOpened opened -> {
        status = AccountStatus.ACTIVE;
        currency = opened.currency();
        owner = opened.owner();
        overdraftLimitMinor = opened.overdraftLimitMinor();
      }
      case MoneyDeposited deposited ->
          balanceMinor = Math.addExact(balanceMinor, deposited.amount().minorUnits());
      case MoneyWithdrawn withdrawn ->
          balanceMinor = Math.subtractExact(balanceMinor, withdrawn.amount().minorUnits());
      case AccountFrozen frozen -> status = AccountStatus.FROZEN;
      case AccountUnfrozen unfrozen -> status = AccountStatus.ACTIVE;
      case AccountClosed closed -> status = AccountStatus.CLOSED;
    }
  }

  private @Nullable DomainError usabilityError() {
    if (status == AccountStatus.NOT_OPENED) {
      return new AccountNotOpened(id);
    }
    if (status == AccountStatus.CLOSED) {
      return new AccountIsClosed(id);
    }
    return null;
  }

  private @Nullable DomainError amountError(Money amount) {
    if (!amount.isPositive()) {
      return new InvalidAmount(amount.minorUnits());
    }
    Currency own = Objects.requireNonNull(currency, "currency of an opened account");
    if (amount.currency() != own) {
      return new CurrencyMismatch(own, amount.currency());
    }
    return null;
  }

  /** Saldo + descubierto, saturado en Long.MAX_VALUE (ambos operandos son &gt;= -limite). */
  private long availableMinor() {
    if (balanceMinor > Long.MAX_VALUE - overdraftLimitMinor) {
      return Long.MAX_VALUE;
    }
    return balanceMinor + overdraftLimitMinor;
  }

  public AccountId id() {
    return id;
  }

  public AccountStatus status() {
    return status;
  }

  public long balanceMinor() {
    return balanceMinor;
  }

  public AccountSnapshot snapshot() {
    return new AccountSnapshot(
        id, status, currency, owner, balanceMinor, overdraftLimitMinor, version());
  }
}
