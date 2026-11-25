package io.github.federiconbaez.banking.application.saga;

import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Money;

/** Pedido de transferencia; {@code transferId} es la clave de idempotencia de toda la saga. */
public record TransferMoney(CommandId transferId, AccountId from, AccountId to, Money amount) {}
