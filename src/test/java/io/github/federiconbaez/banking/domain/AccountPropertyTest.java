package io.github.federiconbaez.banking.domain;

import static io.github.federiconbaez.banking.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

class AccountPropertyTest {

  static final AccountId ID = new AccountId("p-1");

  record Op(int kind, long amount) {}

  @Provide
  Arbitrary<List<Op>> ops() {
    Arbitrary<Op> op =
        Combinators.combine(
                Arbitraries.integers().between(0, 5), Arbitraries.longs().between(-50, 5_000))
            .as(Op::new);
    return op.list().ofMaxSize(60);
  }

  static Account run(long overdraft, List<Op> ops) {
    Account a = Account.fresh(ID);
    a.open("Ana", Currency.ARS, overdraft, T0);
    for (Op op : ops) {
      Money m = Money.of(op.amount(), Currency.ARS);
      switch (op.kind()) {
        case 0, 1 -> a.deposit(m, "d", T0);
        case 2, 3 -> a.withdraw(m, "w", T0);
        case 4 -> a.freeze("f", T0);
        default -> a.unfreeze(T0);
      }
      assertThat(a.balanceMinor()).isGreaterThanOrEqualTo(-overdraft);
    }
    return a;
  }

  @Property(tries = 300)
  void balanceNeverBelowOverdraftPolicy(
      @ForAll @LongRange(min = 0, max = 1_000) long overdraft, @ForAll("ops") List<Op> ops) {
    run(overdraft, ops);
  }

  @Property(tries = 300)
  void replayIsDeterministic(
      @ForAll @LongRange(min = 0, max = 1_000) long overdraft, @ForAll("ops") List<Op> ops) {
    Account live = run(overdraft, ops);
    List<AccountEvent> history = live.uncommittedEvents();
    live.markCommitted();

    AccountSnapshot first = Account.rehydrate(ID, history).snapshot();
    AccountSnapshot second = Account.rehydrate(ID, history).snapshot();

    assertThat(first).isEqualTo(live.snapshot()).isEqualTo(second);
  }

  @Property(tries = 200)
  void balanceEqualsSumOfMovements(@ForAll("ops") List<Op> ops) {
    Account live = run(0, ops);
    long sum = 0;
    for (AccountEvent e : live.uncommittedEvents()) {
      if (e instanceof AccountEvent.MoneyDeposited d) {
        sum += d.amount().minorUnits();
      } else if (e instanceof AccountEvent.MoneyWithdrawn w) {
        sum -= w.amount().minorUnits();
      }
    }
    assertThat(live.balanceMinor()).isEqualTo(sum);
  }
}
