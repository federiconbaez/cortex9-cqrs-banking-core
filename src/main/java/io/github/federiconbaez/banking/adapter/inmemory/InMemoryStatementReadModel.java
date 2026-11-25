package io.github.federiconbaez.banking.adapter.inmemory;

import io.github.federiconbaez.banking.application.port.Projection;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import io.github.federiconbaez.banking.domain.AccountEvent;
import io.github.federiconbaez.banking.domain.AccountId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read model de extracto (movimientos con saldo corrido). */
public final class InMemoryStatementReadModel implements Projection {

  private final Map<AccountId, List<StatementLine>> lines = new HashMap<>();
  private long lastPosition;

  @Override
  public synchronized void on(StoredEvent stored) {
    if (stored.globalPosition() <= lastPosition) {
      return;
    }
    lastPosition = stored.globalPosition();
    switch (stored.event()) {
      case AccountEvent.AccountOpened e -> lines.put(e.accountId(), new ArrayList<>());
      case AccountEvent.MoneyDeposited e ->
          add(stored, e.accountId(), "DEPOSIT", e.amount().minorUnits(), e.reference());
      case AccountEvent.MoneyWithdrawn e ->
          add(stored, e.accountId(), "WITHDRAWAL", -e.amount().minorUnits(), e.reference());
      case AccountEvent.AccountFrozen e -> {}
      case AccountEvent.AccountUnfrozen e -> {}
      case AccountEvent.AccountClosed e -> {}
    }
  }

  private void add(StoredEvent stored, AccountId id, String kind, long signed, String reference) {
    List<StatementLine> account = lines.computeIfAbsent(id, k -> new ArrayList<>());
    long previous = account.isEmpty() ? 0 : account.get(account.size() - 1).balanceAfterMinor();
    account.add(
        new StatementLine(
            stored.version(),
            stored.event().occurredAt(),
            kind,
            signed,
            Math.addExact(previous, signed),
            reference));
  }

  public synchronized List<StatementLine> statementOf(AccountId id) {
    return List.copyOf(lines.getOrDefault(id, List.of()));
  }
}
