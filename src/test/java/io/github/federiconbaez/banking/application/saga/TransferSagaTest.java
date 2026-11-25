package io.github.federiconbaez.banking.application.saga;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.federiconbaez.banking.application.Command;
import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.CommandResult;
import io.github.federiconbaez.banking.application.CommandResult.Accepted;
import io.github.federiconbaez.banking.application.CommandResult.ConcurrencyConflict;
import io.github.federiconbaez.banking.application.CommandResult.IdempotencyKeyReused;
import io.github.federiconbaez.banking.application.CommandResult.Rejected;
import io.github.federiconbaez.banking.application.port.CommandGateway;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.DomainError;
import io.github.federiconbaez.banking.domain.Money;
import io.github.federiconbaez.banking.support.Bank;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferSagaTest {

  static final AccountId A = new AccountId("A");
  static final AccountId B = new AccountId("B");
  static final CommandId TX = new CommandId("tx-1");

  static TransferMoney transfer(long minor) {
    return new TransferMoney(TX, A, B, Money.of(minor, Currency.ARS));
  }

  static Bank funded() {
    Bank bank = new Bank();
    bank.open("A", Currency.ARS, 0);
    bank.open("B", Currency.ARS, 0);
    bank.deposit("A", 100, Currency.ARS);
    return bank;
  }

  /** Fake gateway con respuestas guionadas; registra los comandos recibidos. */
  static final class ScriptedGateway implements CommandGateway {
    final Deque<CommandResult> script;
    final List<Command> received = new ArrayList<>();

    ScriptedGateway(CommandResult... results) {
      script = new ArrayDeque<>(List.of(results));
    }

    @Override
    public CommandResult dispatch(Command command) {
      received.add(command);
      return script.removeFirst();
    }
  }

  static final Accepted OK = new Accepted(A, 1, List.of());
  static final Rejected CLOSED = new Rejected(new DomainError.AccountIsClosed(B));

  @Test
  void completesAndMovesMoney() {
    Bank bank = funded();
    assertThat(new TransferSaga(bank.bus).execute(transfer(40)))
        .isEqualTo(new TransferOutcome.Completed(TX));
    assertThat(bank.balance("A")).isEqualTo(60);
    assertThat(bank.balance("B")).isEqualTo(40);
  }

  @Test
  void rerunningACompletedTransferIsIdempotent() {
    Bank bank = funded();
    TransferSaga saga = new TransferSaga(bank.bus);
    saga.execute(transfer(40));
    assertThat(saga.execute(transfer(40))).isEqualTo(new TransferOutcome.Completed(TX));
    assertThat(bank.balance("A")).isEqualTo(60);
    assertThat(bank.balance("B")).isEqualTo(40);
  }

  @Test
  void sameAccountIsInvalid() {
    ScriptedGateway gateway = new ScriptedGateway();
    TransferOutcome outcome =
        new TransferSaga(gateway).execute(new TransferMoney(TX, A, A, Money.of(1, Currency.ARS)));
    assertThat(outcome).isInstanceOf(TransferOutcome.InvalidTransfer.class);
    assertThat(outcome.transferId()).isEqualTo(TX);
    assertThat(gateway.received).isEmpty();
  }

  @Test
  void debitRejectedMovesNothing() {
    Bank bank = funded();
    assertThat(new TransferSaga(bank.bus).execute(transfer(101)))
        .isEqualTo(
            new TransferOutcome.DebitRejected(TX, new DomainError.InsufficientFunds(100, 101)));
    assertThat(bank.balance("A")).isEqualTo(100);
    assertThat(bank.balance("B")).isZero();
  }

  @Test
  void creditRejectedIsCompensated() {
    Bank bank = funded();
    bank.bus.dispatch(new Command.CloseAccount(bank.nextId(), B));

    assertThat(new TransferSaga(bank.bus).execute(transfer(40)))
        .isEqualTo(new TransferOutcome.Compensated(TX, new DomainError.AccountIsClosed(B)));
    assertThat(bank.balance("A")).isEqualTo(100);
    assertThat(bank.store.load("account-A"))
        .extracting(e -> e.causationId().value())
        .contains("tx-1:debit", "tx-1:compensate");
  }

  @Test
  void currencyMismatchOnTargetIsCompensated() {
    Bank bank = funded();
    bank.open("U", Currency.USD, 0);
    TransferOutcome outcome =
        new TransferSaga(bank.bus)
            .execute(new TransferMoney(TX, A, new AccountId("U"), Money.of(10, Currency.ARS)));
    assertThat(outcome)
        .isEqualTo(
            new TransferOutcome.Compensated(
                TX, new DomainError.CurrencyMismatch(Currency.USD, Currency.ARS)));
    assertThat(bank.balance("A")).isEqualTo(100);
  }

  @Test
  void stepsUseDerivedIdempotencyKeys() {
    ScriptedGateway gateway = new ScriptedGateway(OK, CLOSED, OK);
    new TransferSaga(gateway).execute(transfer(5));
    assertThat(gateway.received)
        .extracting(c -> c.commandId().value())
        .containsExactly("tx-1:debit", "tx-1:credit", "tx-1:compensate");
    assertThat(gateway.received).extracting(Command::accountId).containsExactly(A, B, A);
  }

  @Test
  void compensationRejectedNeedsManualIntervention() {
    Rejected refundRejected = new Rejected(new DomainError.AccountIsClosed(A));
    TransferOutcome outcome =
        new TransferSaga(new ScriptedGateway(OK, CLOSED, refundRejected)).execute(transfer(5));
    assertThat(outcome)
        .isEqualTo(
            new TransferOutcome.CompensationFailed(
                TX, new DomainError.AccountIsClosed(B), refundRejected));
  }

  @Test
  void transientFailuresArePendingAtEachStep() {
    ConcurrencyConflict conflict = new ConcurrencyConflict(3);
    assertThat(new TransferSaga(new ScriptedGateway(conflict)).execute(transfer(5)))
        .isEqualTo(new TransferOutcome.Pending(TX, "debit", conflict));
    assertThat(new TransferSaga(new ScriptedGateway(OK, conflict)).execute(transfer(5)))
        .isEqualTo(new TransferOutcome.Pending(TX, "credit", conflict));
    assertThat(new TransferSaga(new ScriptedGateway(OK, CLOSED, conflict)).execute(transfer(5)))
        .isEqualTo(new TransferOutcome.Pending(TX, "compensate", conflict));
  }

  @Test
  void reusedKeysAbort() {
    IdempotencyKeyReused reused = new IdempotencyKeyReused(TX);
    assertThat(new TransferSaga(new ScriptedGateway(reused)).execute(transfer(5)))
        .isEqualTo(new TransferOutcome.Aborted(TX, "debit", reused));
    assertThat(new TransferSaga(new ScriptedGateway(OK, reused)).execute(transfer(5)))
        .isEqualTo(new TransferOutcome.Aborted(TX, "credit", reused));
  }
}
