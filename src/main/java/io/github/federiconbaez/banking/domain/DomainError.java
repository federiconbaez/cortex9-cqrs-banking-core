package io.github.federiconbaez.banking.domain;

/** Errores de negocio tipados y cerrados: el compilador obliga a manejarlos todos. */
public sealed interface DomainError {

  record AccountNotOpened(AccountId accountId) implements DomainError {}

  record AccountAlreadyOpened(AccountId accountId) implements DomainError {}

  record AccountIsClosed(AccountId accountId) implements DomainError {}

  record AccountIsFrozen(AccountId accountId) implements DomainError {}

  record AccountNotFrozen(AccountId accountId) implements DomainError {}

  record InvalidOwner(String owner) implements DomainError {}

  record InvalidOverdraftLimit(long limitMinor) implements DomainError {}

  record InvalidAmount(long minorUnits) implements DomainError {}

  record CurrencyMismatch(Currency expected, Currency actual) implements DomainError {}

  record InsufficientFunds(long availableMinor, long requestedMinor) implements DomainError {}

  record BalanceOverflow(long balanceMinor, long amountMinor) implements DomainError {}

  record NonZeroBalance(long balanceMinor) implements DomainError {}
}
