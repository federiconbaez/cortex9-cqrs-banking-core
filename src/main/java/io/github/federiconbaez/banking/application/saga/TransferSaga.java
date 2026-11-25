package io.github.federiconbaez.banking.application.saga;

import io.github.federiconbaez.banking.application.Command.Deposit;
import io.github.federiconbaez.banking.application.Command.Withdraw;
import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.CommandResult;
import io.github.federiconbaez.banking.application.CommandResult.Accepted;
import io.github.federiconbaez.banking.application.CommandResult.ConcurrencyConflict;
import io.github.federiconbaez.banking.application.CommandResult.Rejected;
import io.github.federiconbaez.banking.application.port.CommandGateway;
import io.github.federiconbaez.banking.application.saga.TransferOutcome.Aborted;
import io.github.federiconbaez.banking.application.saga.TransferOutcome.Compensated;
import io.github.federiconbaez.banking.application.saga.TransferOutcome.CompensationFailed;
import io.github.federiconbaez.banking.application.saga.TransferOutcome.Completed;
import io.github.federiconbaez.banking.application.saga.TransferOutcome.DebitRejected;
import io.github.federiconbaez.banking.application.saga.TransferOutcome.InvalidTransfer;
import io.github.federiconbaez.banking.application.saga.TransferOutcome.Pending;

/**
 * Saga orquestada (process manager liviano): debito -&gt; credito -&gt; compensacion si el credito
 * es rechazado. Cada paso usa una clave derivada del transferId, asi que reejecutar la saga es
 * idempotente y reanuda donde quedo. Solo se compensa ante un rechazo definitivo (cacheado): un
 * conflicto transitorio devuelve {@link Pending} para no acreditar y reembolsar a la vez.
 */
public final class TransferSaga {

  private final CommandGateway gateway;

  public TransferSaga(CommandGateway gateway) {
    this.gateway = gateway;
  }

  public TransferOutcome execute(TransferMoney transfer) {
    CommandId id = transfer.transferId();
    if (transfer.from().equals(transfer.to())) {
      return new InvalidTransfer(id, "source and target must differ");
    }
    String reference = "transfer:" + id.value();

    CommandResult debit =
        gateway.dispatch(
            new Withdraw(step(id, "debit"), transfer.from(), transfer.amount(), reference));
    if (debit instanceof Rejected rejected) {
      return new DebitRejected(id, rejected.error());
    }
    if (!(debit instanceof Accepted)) {
      return interrupted(id, "debit", debit);
    }

    CommandResult credit =
        gateway.dispatch(
            new Deposit(step(id, "credit"), transfer.to(), transfer.amount(), reference));
    if (credit instanceof Accepted) {
      return new Completed(id);
    }
    if (!(credit instanceof Rejected creditRejected)) {
      return interrupted(id, "credit", credit);
    }

    CommandResult refund =
        gateway.dispatch(
            new Deposit(
                step(id, "compensate"), transfer.from(), transfer.amount(), reference + ":refund"));
    if (refund instanceof Accepted) {
      return new Compensated(id, creditRejected.error());
    }
    if (refund instanceof Rejected) {
      return new CompensationFailed(id, creditRejected.error(), refund);
    }
    return interrupted(id, "compensate", refund);
  }

  private static TransferOutcome interrupted(CommandId id, String step, CommandResult result) {
    if (result instanceof ConcurrencyConflict) {
      return new Pending(id, step, result);
    }
    return new Aborted(id, step, result);
  }

  static CommandId step(CommandId transferId, String step) {
    return new CommandId(transferId.value() + ":" + step);
  }
}
