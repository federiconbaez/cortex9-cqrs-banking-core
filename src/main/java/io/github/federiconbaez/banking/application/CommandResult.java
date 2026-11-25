package io.github.federiconbaez.banking.application;

import io.github.federiconbaez.banking.domain.AccountEvent;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.DomainError;
import java.util.List;

/** Resultado cerrado de despachar un comando. */
public sealed interface CommandResult {

  /** Persistido: {@code version} es la version del stream tras el append. */
  record Accepted(AccountId accountId, long version, List<AccountEvent> events)
      implements CommandResult {
    public Accepted {
      events = List.copyOf(events);
    }
  }

  /** Rechazo de negocio definitivo (se cachea por idempotencia). */
  record Rejected(DomainError error) implements CommandResult {}

  /** Se agotaron los reintentos por concurrencia optimista. Transitorio: NO se cachea. */
  record ConcurrencyConflict(int attempts) implements CommandResult {}

  /** La misma clave de idempotencia ya se uso con un payload distinto. */
  record IdempotencyKeyReused(CommandId commandId) implements CommandResult {}
}
