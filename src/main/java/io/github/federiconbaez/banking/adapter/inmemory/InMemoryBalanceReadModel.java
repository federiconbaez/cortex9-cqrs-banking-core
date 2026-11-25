package io.github.federiconbaez.banking.adapter.inmemory;

import io.github.federiconbaez.banking.application.port.Projection;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import io.github.federiconbaez.banking.domain.AccountEvent;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.Money;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Read model de saldos por cuenta. Se ignoran eventos ya vistos (por posicion global). */
public final class InMemoryBalanceReadModel implements Projection {

  private final Map<AccountId, Money> balances = new ConcurrentHashMap<>();
  private long lastPosition;

  @Override
  public synchronized void on(StoredEvent stored) {
    if (stored.globalPosition() <= lastPosition) {
      return;
    }
    lastPosition = stored.globalPosition();
    switch (stored.event()) {
      case AccountEvent.AccountOpened e -> balances.put(e.accountId(), Money.zero(e.currency()));
      case AccountEvent.MoneyDeposited e ->
          balances.computeIfPresent(e.accountId(), (k, b) -> b.plus(e.amount()));
      case AccountEvent.MoneyWithdrawn e ->
          balances.computeIfPresent(e.accountId(), (k, b) -> b.plus(e.amount().negate()));
      case AccountEvent.AccountFrozen e -> {}
      case AccountEvent.AccountUnfrozen e -> {}
      case AccountEvent.AccountClosed e -> {}
    }
  }

  public Optional<Money> balanceOf(AccountId id) {
    return Optional.ofNullable(balances.get(id));
  }

  /** Suma de saldos en una moneda (deberia conservarse ante transferencias). */
  public long totalMinor(Currency currency) {
    return balances.values().stream()
        .filter(m -> m.currency() == currency)
        .mapToLong(Money::minorUnits)
        .sum();
  }
}
