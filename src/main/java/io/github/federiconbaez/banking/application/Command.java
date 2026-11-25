package io.github.federiconbaez.banking.application;

import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.Money;

/** Comandos (patron Command): intenciones inmutables dirigidas a un agregado. */
public sealed interface Command {

  CommandId commandId();

  AccountId accountId();

  record OpenAccount(
      CommandId commandId,
      AccountId accountId,
      String owner,
      Currency currency,
      long overdraftLimitMinor)
      implements Command {}

  record Deposit(CommandId commandId, AccountId accountId, Money amount, String reference)
      implements Command {}

  record Withdraw(CommandId commandId, AccountId accountId, Money amount, String reference)
      implements Command {}

  record FreezeAccount(CommandId commandId, AccountId accountId, String reason)
      implements Command {}

  record UnfreezeAccount(CommandId commandId, AccountId accountId) implements Command {}

  record CloseAccount(CommandId commandId, AccountId accountId) implements Command {}
}
