package io.github.federiconbaez.banking.adapter.inmemory;

import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.port.IdempotencyRecord;
import io.github.federiconbaez.banking.application.port.IdempotencyStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Store de idempotencia en memoria, first-writer-wins. Sin TTL ni durabilidad. */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

  private final Map<CommandId, IdempotencyRecord> records = new ConcurrentHashMap<>();

  @Override
  public Optional<IdempotencyRecord> find(CommandId commandId) {
    return Optional.ofNullable(records.get(commandId));
  }

  @Override
  public void save(CommandId commandId, IdempotencyRecord idempotencyRecord) {
    records.putIfAbsent(commandId, idempotencyRecord);
  }

  public int size() {
    return records.size();
  }
}
