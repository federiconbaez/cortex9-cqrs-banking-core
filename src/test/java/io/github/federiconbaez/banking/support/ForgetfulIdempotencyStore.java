package io.github.federiconbaez.banking.support;

import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.port.IdempotencyRecord;
import io.github.federiconbaez.banking.application.port.IdempotencyStore;
import java.util.Optional;

/** Fake: simula un crash entre el append y el guardado de idempotencia (nunca recuerda). */
public final class ForgetfulIdempotencyStore implements IdempotencyStore {
  @Override
  public Optional<IdempotencyRecord> find(CommandId commandId) {
    return Optional.empty();
  }

  @Override
  public void save(CommandId commandId, IdempotencyRecord idempotencyRecord) {}
}
