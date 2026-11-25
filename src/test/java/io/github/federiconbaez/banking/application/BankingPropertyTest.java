package io.github.federiconbaez.banking.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.federiconbaez.banking.adapter.inmemory.InMemoryBalanceReadModel;
import io.github.federiconbaez.banking.application.projection.ProjectionRunner;
import io.github.federiconbaez.banking.application.saga.TransferMoney;
import io.github.federiconbaez.banking.application.saga.TransferSaga;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.Money;
import io.github.federiconbaez.banking.support.Bank;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class BankingPropertyTest {

  static final List<String> IDS = List.of("A", "B", "C", "D");

  /** kind 0 = transferencia, 1 = congelar destino, 2 = cerrar destino (si saldo 0). */
  record Step(int kind, int from, int to, long amount) {}

  @Provide
  Arbitrary<List<Step>> steps() {
    return Combinators.combine(
            Arbitraries.integers().between(0, 9).map(k -> k < 8 ? 0 : k - 7),
            Arbitraries.integers().between(0, 3),
            Arbitraries.integers().between(0, 3),
            Arbitraries.longs().between(-5, 400))
        .as(Step::new)
        .list()
        .ofMaxSize(40);
  }

  @Property(tries = 150)
  void transfersConserveTotalMoney(@ForAll("steps") List<Step> steps) {
    Bank bank = new Bank();
    for (String id : IDS) {
      bank.open(id, Currency.ARS, id.equals("D") ? 100 : 0);
    }
    bank.deposit("A", 500, Currency.ARS);
    bank.deposit("B", 300, Currency.ARS);
    long total = 800;
    TransferSaga saga = new TransferSaga(bank.bus);
    int n = 0;
    for (Step s : steps) {
      AccountId to = new AccountId(IDS.get(s.to()));
      switch (s.kind()) {
        case 0 ->
            saga.execute(
                new TransferMoney(
                    new CommandId("tx-" + n++),
                    new AccountId(IDS.get(s.from())),
                    to,
                    Money.of(s.amount(), Currency.ARS)));
        case 1 -> bank.bus.dispatch(new Command.FreezeAccount(bank.nextId(), to, "p"));
        default -> bank.bus.dispatch(new Command.CloseAccount(bank.nextId(), to));
      }
      long sum = IDS.stream().mapToLong(bank::balance).sum();
      assertThat(sum).isEqualTo(total);
    }
    InMemoryBalanceReadModel balances = new InMemoryBalanceReadModel();
    new ProjectionRunner(bank.store, List.of(balances), 7).catchUp();
    assertThat(balances.totalMinor(Currency.ARS)).isEqualTo(total);
    for (String id : IDS) {
      assertThat(balances.balanceOf(new AccountId(id)))
          .contains(Money.of(bank.balance(id), Currency.ARS));
    }
  }

  @Provide
  Arbitrary<Command> commands() {
    AccountId a = new AccountId("A");
    CommandId id = new CommandId("same-key");
    Arbitrary<Long> amount = Arbitraries.longs().between(-10, 2_000);
    return Arbitraries.oneOf(
        amount.map(m -> new Command.Deposit(id, a, Money.of(m, Currency.ARS), "d")),
        amount.map(m -> new Command.Withdraw(id, a, Money.of(m, Currency.ARS), "w")),
        amount.map(m -> new Command.Deposit(id, a, Money.of(m, Currency.USD), "d")),
        Arbitraries.just(new Command.FreezeAccount(id, a, "f")),
        Arbitraries.just(new Command.UnfreezeAccount(id, a)),
        Arbitraries.just(new Command.CloseAccount(id, a)),
        Arbitraries.just(new Command.OpenAccount(id, a, "x", Currency.ARS, 0)));
  }

  @Property(tries = 300)
  void idempotentCommandYieldsSameResultAndNoExtraEvents(
      @ForAll("commands") Command command, @ForAll boolean seedBalance) {
    Bank bank = new Bank();
    bank.open("A", Currency.ARS, 0);
    if (seedBalance) {
      bank.deposit("A", 1_000, Currency.ARS);
    }
    CommandResult first = bank.bus.dispatch(command);
    int events = bank.store.load("account-A").size();

    CommandResult second = bank.bus.dispatch(command);

    assertThat(second).isEqualTo(first);
    assertThat(bank.store.load("account-A")).hasSize(events);
  }
}
