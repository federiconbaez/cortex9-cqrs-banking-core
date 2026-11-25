package io.github.federiconbaez.banking.application;

import static io.github.federiconbaez.banking.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.federiconbaez.banking.adapter.inmemory.InMemoryBalanceReadModel;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryEventStore;
import io.github.federiconbaez.banking.adapter.inmemory.InMemoryStatementReadModel;
import io.github.federiconbaez.banking.adapter.inmemory.StatementLine;
import io.github.federiconbaez.banking.application.projection.ProjectionRunner;
import io.github.federiconbaez.banking.application.saga.TransferMoney;
import io.github.federiconbaez.banking.application.saga.TransferSaga;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.Money;
import io.github.federiconbaez.banking.support.Bank;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectionTest {

  static final AccountId A = new AccountId("A");
  static final AccountId B = new AccountId("B");

  @Test
  void balancesAndStatementFollowTheLog() {
    Bank bank = new Bank();
    bank.open("A", Currency.ARS, 0);
    bank.open("B", Currency.ARS, 0);
    bank.deposit("A", 100, Currency.ARS);
    new TransferSaga(bank.bus)
        .execute(new TransferMoney(new CommandId("t1"), A, B, Money.of(30, Currency.ARS)));
    bank.bus.dispatch(new Command.FreezeAccount(bank.nextId(), B, "kyc"));

    InMemoryBalanceReadModel balances = new InMemoryBalanceReadModel();
    InMemoryStatementReadModel statements = new InMemoryStatementReadModel();
    ProjectionRunner runner = new ProjectionRunner(bank.store, List.of(balances, statements), 2);

    assertThat(runner.catchUp()).isEqualTo(6);
    assertThat(runner.checkpoint()).isEqualTo(6);
    assertThat(runner.catchUp()).isZero();

    assertThat(balances.balanceOf(A)).contains(Money.of(70, Currency.ARS));
    assertThat(balances.balanceOf(B)).contains(Money.of(30, Currency.ARS));
    assertThat(balances.balanceOf(new AccountId("missing"))).isEmpty();
    assertThat(balances.totalMinor(Currency.ARS)).isEqualTo(100);
    assertThat(balances.totalMinor(Currency.USD)).isZero();

    assertThat(statements.statementOf(A))
        .containsExactly(
            new StatementLine(2, T0, "DEPOSIT", 100, 100, "seed"),
            new StatementLine(3, T0, "WITHDRAWAL", -30, 70, "transfer:t1"));
    assertThat(statements.statementOf(B))
        .containsExactly(new StatementLine(2, T0, "DEPOSIT", 30, 30, "transfer:t1"));
    assertThat(statements.statementOf(new AccountId("missing"))).isEmpty();
  }

  @Test
  void incrementalCatchUpAndDuplicateDeliveryAreSafe() {
    Bank bank = new Bank();
    bank.open("A", Currency.ARS, 0);
    InMemoryBalanceReadModel balances = new InMemoryBalanceReadModel();
    InMemoryStatementReadModel statements = new InMemoryStatementReadModel();
    ProjectionRunner runner = new ProjectionRunner(bank.store, List.of(balances, statements), 10);
    runner.catchUp();
    bank.deposit("A", 5, Currency.ARS);
    assertThat(runner.catchUp()).isEqualTo(1);

    // redelivery (at-least-once): los read models ignoran posiciones ya vistas
    bank.store
        .readAll(0, 10)
        .forEach(
            e -> {
              balances.on(e);
              statements.on(e);
            });
    assertThat(balances.balanceOf(A)).contains(Money.of(5, Currency.ARS));
    assertThat(statements.statementOf(A)).hasSize(1);
  }

  @Test
  void closedAndUnfrozenAccountsKeepTheirBalanceRow() {
    Bank bank = new Bank();
    bank.open("A", Currency.ARS, 0);
    bank.bus.dispatch(new Command.FreezeAccount(bank.nextId(), A, "x"));
    bank.bus.dispatch(new Command.UnfreezeAccount(bank.nextId(), A));
    bank.bus.dispatch(new Command.CloseAccount(bank.nextId(), A));
    InMemoryBalanceReadModel balances = new InMemoryBalanceReadModel();
    InMemoryStatementReadModel statements = new InMemoryStatementReadModel();
    new ProjectionRunner(bank.store, List.of(balances, statements), 1).catchUp();
    assertThat(balances.balanceOf(A)).contains(Money.zero(Currency.ARS));
    assertThat(statements.statementOf(A)).isEmpty();
  }

  @Test
  void rejectsInvalidBatchSize() {
    assertThatThrownBy(() -> new ProjectionRunner(new InMemoryEventStore(), List.of(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
