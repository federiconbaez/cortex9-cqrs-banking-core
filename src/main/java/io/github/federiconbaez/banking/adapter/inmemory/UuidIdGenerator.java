package io.github.federiconbaez.banking.adapter.inmemory;

import io.github.federiconbaez.banking.application.port.IdGenerator;
import java.util.UUID;

/** Ids aleatorios UUIDv4. */
public final class UuidIdGenerator implements IdGenerator {
  @Override
  public String newId() {
    return UUID.randomUUID().toString();
  }
}
