package io.github.federiconbaez.banking.domain;

import static io.github.federiconbaez.banking.support.Fixtures.T0;
import static io.github.federiconbaez.banking.support.Fixtures.err;
import static io.github.federiconbaez.banking.support.Fixtures.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AccountTest {

  static final AccountId ID = new AccountId("acc-1");

  static Money ars(long minor) {
    return Money.of(minor, Currency.ARS);
  }

  static Account opened(long overdraft) {
    Account a = Account.fresh(ID);
    ok(a.open("Ana", Currency.ARS, overdraft, T0));
    return a;
  }

  static Account withBalance(long balance) {
    Account a = opened(0);
    if (balance > 0) {
      ok(a.deposit(ars(balance), "seed", T0));
    }
    return a;
  }

  @Test
  void freshAccountIsNotOpened() {
    Account a = Account.fresh(ID);
    assertThat(a.status()).isEqualTo(AccountStatus.NOT_OPENED);
    assertThat(a.version()).isZero();
    assertThat(a.id()).isEqualTo(ID);
    assertThatThrownBy(() -> Account.fresh(null)).isInstanceOf(NullPointerException.class);
  }

  @Nested
  class Open {
    @Test
    void emitsOpenedAndActivates() {
      Account a = Account.fresh(ID);
      AccountEvent e = ok(a.open("Ana", Currency.USD, 500, T0));
      assertThat(e).isEqualTo(new AccountOpened(ID, "Ana", Currency.USD, 500, T0));
      assertThat(a.snapshot())
          .isEqualTo(new AccountSnapshot(ID, AccountStatus.ACTIVE, Currency.USD, "Ana", 0, 500, 0));
      assertThat(a.uncommittedEvents()).containsExactly(e);
    }

    @Test
    void zeroOverdraftIsValid() {
      assertThat(Account.fresh(ID).open("Ana", Currency.ARS, 0, T0).isOk()).isTrue();
    }

    @Test
    void rejectsSecondOpen() {
      Account a = opened(0);
      assertThat(err(a.open("Ana", Currency.ARS, 0, T0))).isEqualTo(new AccountAlreadyOpened(ID));
      assertThat(a.uncommittedEvents()).hasSize(1);
    }

    @Test
    void rejectsBlankOwner() {
      Account a = Account.fresh(ID);
      assertThat(err(a.open("  ", Currency.ARS, 0, T0))).isEqualTo(new InvalidOwner("  "));
      assertThat(a.uncommittedEvents()).isEmpty();
      assertThat(a.status()).isEqualTo(AccountStatus.NOT_OPENED);
    }

    @Test
    void rejectsNegativeOverdraft() {
      assertThat(err(Account.fresh(ID).open("Ana", Currency.ARS, -1, T0)))
          .isEqualTo(new InvalidOverdraftLimit(-1));
    }
  }

  @Nested
  class Deposit {
    @Test
    void increasesBalance() {
      Account a = opened(0);
      AccountEvent e = ok(a.deposit(ars(1050), "ref", T0));
      assertThat(e).isEqualTo(new MoneyDeposited(ID, ars(1050), "ref", T0));
      assertThat(a.balanceMinor()).isEqualTo(1050);
    }

    @Test
    void rejectsWhenNotOpened() {
      assertThat(err(Account.fresh(ID).deposit(ars(1), "r", T0)))
          .isEqualTo(new AccountNotOpened(ID));
    }

    @Test
    void rejectsWhenClosed() {
      Account a = opened(0);
      ok(a.close(T0));
      assertThat(err(a.deposit(ars(1), "r", T0))).isEqualTo(new AccountIsClosed(ID));
    }

    @Test
    void allowedWhenFrozen() {
      Account a = opened(0);
      ok(a.freeze("kyc", T0));
      assertThat(a.deposit(ars(5), "r", T0).isOk()).isTrue();
      assertThat(a.balanceMinor()).isEqualTo(5);
    }

    @Test
    void rejectsNonPositiveAmounts() {
      Account a = opened(0);
      assertThat(err(a.deposit(ars(0), "r", T0))).isEqualTo(new InvalidAmount(0));
      assertThat(err(a.deposit(ars(-3), "r", T0))).isEqualTo(new InvalidAmount(-3));
      assertThat(a.uncommittedEvents()).hasSize(1);
    }

    @Test
    void rejectsOtherCurrency() {
      assertThat(err(opened(0).deposit(Money.of(1, Currency.USD), "r", T0)))
          .isEqualTo(new CurrencyMismatch(Currency.ARS, Currency.USD));
    }

    @Test
    void rejectsOverflowButAcceptsExactMax() {
      Account a = withBalance(10);
      assertThat(a.deposit(ars(Long.MAX_VALUE - 10), "r", T0).isOk()).isTrue();
      assertThat(a.balanceMinor()).isEqualTo(Long.MAX_VALUE);
      assertThat(err(a.deposit(ars(1), "r", T0))).isEqualTo(new BalanceOverflow(Long.MAX_VALUE, 1));
    }
  }

  @Nested
  class Withdraw {
    @Test
    void decreasesBalance() {
      Account a = withBalance(100);
      AccountEvent e = ok(a.withdraw(ars(40), "w", T0));
      assertThat(e).isEqualTo(new MoneyWithdrawn(ID, ars(40), "w", T0));
      assertThat(a.balanceMinor()).isEqualTo(60);
    }

    @Test
    void exactBalanceIsAllowedOneMoreIsNot() {
      Account a = withBalance(100);
      assertThat(err(a.withdraw(ars(101), "w", T0))).isEqualTo(new InsufficientFunds(100, 101));
      assertThat(a.withdraw(ars(100), "w", T0).isOk()).isTrue();
      assertThat(a.balanceMinor()).isZero();
    }

    @Test
    void overdraftAllowsNegativeUpToLimit() {
      Account a = opened(50);
      assertThat(err(a.withdraw(ars(51), "w", T0))).isEqualTo(new InsufficientFunds(50, 51));
      assertThat(a.withdraw(ars(50), "w", T0).isOk()).isTrue();
      assertThat(a.balanceMinor()).isEqualTo(-50);
      assertThat(err(a.withdraw(ars(1), "w", T0))).isEqualTo(new InsufficientFunds(0, 1));
    }

    @Test
    void availableSaturatesInsteadOfOverflowing() {
      Account a = Account.fresh(ID);
      ok(a.open("Ana", Currency.ARS, Long.MAX_VALUE, T0));
      ok(a.deposit(ars(1), "seed", T0));
      assertThat(a.withdraw(ars(Long.MAX_VALUE), "w", T0).isOk()).isTrue();
      assertThat(a.balanceMinor()).isEqualTo(1 - Long.MAX_VALUE);
    }

    @Test
    void availableIsExactAtSaturationBoundary() {
      Account a = Account.fresh(ID);
      ok(a.open("Ana", Currency.ARS, Long.MAX_VALUE - 1, T0));
      ok(a.deposit(ars(1), "seed", T0));
      assertThat(a.withdraw(ars(Long.MAX_VALUE), "w", T0).isOk()).isTrue();
    }

    @Test
    void rejectsWhenFrozen() {
      Account a = withBalance(100);
      ok(a.freeze("kyc", T0));
      assertThat(err(a.withdraw(ars(1), "w", T0))).isEqualTo(new AccountIsFrozen(ID));
    }

    @Test
    void rejectsWhenNotOpenedOrClosed() {
      assertThat(err(Account.fresh(ID).withdraw(ars(1), "w", T0)))
          .isEqualTo(new AccountNotOpened(ID));
      Account a = opened(0);
      ok(a.close(T0));
      assertThat(err(a.withdraw(ars(1), "w", T0))).isEqualTo(new AccountIsClosed(ID));
    }

    @Test
    void rejectsInvalidAmountAndCurrency() {
      Account a = withBalance(100);
      assertThat(err(a.withdraw(ars(0), "w", T0))).isEqualTo(new InvalidAmount(0));
      assertThat(err(a.withdraw(Money.of(1, Currency.EUR), "w", T0)))
          .isEqualTo(new CurrencyMismatch(Currency.ARS, Currency.EUR));
      assertThat(a.balanceMinor()).isEqualTo(100);
    }
  }

  @Nested
  class Lifecycle {
    @Test
    void freezeAndUnfreeze() {
      Account a = opened(0);
      assertThat(ok(a.freeze("kyc", T0))).isEqualTo(new AccountFrozen(ID, "kyc", T0));
      assertThat(a.status()).isEqualTo(AccountStatus.FROZEN);
      assertThat(err(a.freeze("again", T0))).isEqualTo(new AccountIsFrozen(ID));
      assertThat(ok(a.unfreeze(T0))).isEqualTo(new AccountUnfrozen(ID, T0));
      assertThat(a.status()).isEqualTo(AccountStatus.ACTIVE);
      assertThat(err(a.unfreeze(T0))).isEqualTo(new AccountNotFrozen(ID));
    }

    @Test
    void freezeAndUnfreezeRequireUsableAccount() {
      assertThat(err(Account.fresh(ID).freeze("x", T0))).isEqualTo(new AccountNotOpened(ID));
      assertThat(err(Account.fresh(ID).unfreeze(T0))).isEqualTo(new AccountNotOpened(ID));
      Account a = opened(0);
      ok(a.close(T0));
      assertThat(err(a.freeze("x", T0))).isEqualTo(new AccountIsClosed(ID));
      assertThat(err(a.unfreeze(T0))).isEqualTo(new AccountIsClosed(ID));
    }

    @Test
    void closeRequiresZeroBalance() {
      Account a = withBalance(10);
      assertThat(err(a.close(T0))).isEqualTo(new NonZeroBalance(10));
      ok(a.withdraw(ars(10), "w", T0));
      assertThat(ok(a.close(T0))).isEqualTo(new AccountClosed(ID, T0));
      assertThat(a.status()).isEqualTo(AccountStatus.CLOSED);
      assertThat(err(a.close(T0))).isEqualTo(new AccountIsClosed(ID));
    }

    @Test
    void closeRejectsNegativeBalanceAndUnopened() {
      Account a = opened(10);
      ok(a.withdraw(ars(3), "w", T0));
      assertThat(err(a.close(T0))).isEqualTo(new NonZeroBalance(-3));
      assertThat(err(Account.fresh(ID).close(T0))).isEqualTo(new AccountNotOpened(ID));
    }

    @Test
    void frozenAccountCanBeClosed() {
      Account a = opened(0);
      ok(a.freeze("x", T0));
      assertThat(a.close(T0).isOk()).isTrue();
    }
  }

  @Nested
  class EventSourcing {
    @Test
    void rehydrateRebuildsStateAndVersion() {
      Account live = withBalance(100);
      ok(live.withdraw(ars(30), "w", T0));
      ok(live.freeze("x", T0));
      List<AccountEvent> history = live.uncommittedEvents();

      Account replayed = Account.rehydrate(ID, history);

      assertThat(replayed.version()).isEqualTo(4);
      assertThat(replayed.uncommittedEvents()).isEmpty();
      assertThat(replayed.balanceMinor()).isEqualTo(70);
      assertThat(replayed.status()).isEqualTo(AccountStatus.FROZEN);
      live.markCommitted();
      assertThat(replayed.snapshot()).isEqualTo(live.snapshot());
    }

    @Test
    void markCommittedAdvancesVersion() {
      Account a = withBalance(5);
      assertThat(a.version()).isZero();
      a.markCommitted();
      assertThat(a.version()).isEqualTo(2);
      assertThat(a.uncommittedEvents()).isEmpty();
      ok(a.deposit(ars(1), "r", T0));
      a.markCommitted();
      assertThat(a.version()).isEqualTo(3);
    }

    @Test
    void uncommittedEventsIsDefensiveCopy() {
      Account a = opened(0);
      assertThatThrownBy(() -> a.uncommittedEvents().clear())
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unfreezeEventReactivatesOnReplay() {
      Account a =
          Account.rehydrate(
              ID,
              List.of(
                  new AccountOpened(ID, "Ana", Currency.ARS, 0, T0),
                  new AccountFrozen(ID, "x", T0),
                  new AccountUnfrozen(ID, T0),
                  new AccountClosed(ID, T0)));
      assertThat(a.status()).isEqualTo(AccountStatus.CLOSED);
      assertThat(a.snapshot().owner()).isEqualTo("Ana");
      assertThat(a.snapshot().currency()).isEqualTo(Currency.ARS);
    }
  }
}
