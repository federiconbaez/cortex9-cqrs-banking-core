package io.github.federiconbaez.banking.application.port;

import io.github.federiconbaez.banking.application.CommandId;
import java.util.Optional;

/** Puerto de dedupe de comandos. {@code save} es first-writer-wins. */
public interface IdempotencyStore {

  Optional<IdempotencyRecord> find(CommandId commandId);

  void save(CommandId commandId, IdempotencyRecord idempotencyRecord);
}
