package io.github.federiconbaez.banking.domain;

import java.time.Instant;

/** Eventos de dominio (patron Event): hechos inmutables, fuente de verdad del agregado. */
public sealed interface AccountEvent {

  AccountId accountId();

  Instant occurredAt();

  record AccountOpened(
      AccountId accountId,
      String owner,
      Currency currency,
      long overdraftLimitMinor,
      Instant occurredAt)
      implements AccountEvent {}

  record MoneyDeposited(AccountId accountId, Money amount, String reference, Instant occurredAt)
      implements AccountEvent {}

  record MoneyWithdrawn(AccountId accountId, Money amount, String reference, Instant occurredAt)
      implements AccountEvent {}

  record AccountFrozen(AccountId accountId, String reason, Instant occurredAt)
      implements AccountEvent {}

  record AccountUnfrozen(AccountId accountId, Instant occurredAt) implements AccountEvent {}

  record AccountClosed(AccountId accountId, Instant occurredAt) implements AccountEvent {}
}
