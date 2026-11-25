package io.github.federiconbaez.banking.application.saga;

import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.CommandResult;
import io.github.federiconbaez.banking.domain.DomainError;

/** Estados finales (o reanudables) de la saga de transferencia. */
public sealed interface TransferOutcome {

  CommandId transferId();

  record Completed(CommandId transferId) implements TransferOutcome {}

  /** El debito fue rechazado: no hubo movimiento de dinero. */
  record DebitRejected(CommandId transferId, DomainError error) implements TransferOutcome {}

  /** El credito fue rechazado y el debito se revirtio con un deposito compensatorio. */
  record Compensated(CommandId transferId, DomainError creditError) implements TransferOutcome {}

  /** El credito y la compensacion fueron rechazados: requiere intervencion manual. */
  record CompensationFailed(
      CommandId transferId, DomainError creditError, CommandResult compensation)
      implements TransferOutcome {}

  /** Fallo transitorio en {@code step}: reejecutar con el mismo transferId es seguro. */
  record Pending(CommandId transferId, String step, CommandResult lastResult)
      implements TransferOutcome {}

  /** Error no reintentable (p.ej. clave de idempotencia reutilizada). */
  record Aborted(CommandId transferId, String step, CommandResult lastResult)
      implements TransferOutcome {}

  record InvalidTransfer(CommandId transferId, String reason) implements TransferOutcome {}
}
