package io.github.federiconbaez.banking.adapter.inmemory;

import io.github.federiconbaez.banking.application.port.IdGenerator;
import java.util.concurrent.atomic.AtomicLong;

/** Ids deterministas "prefijo-n" para tests y replays reproducibles. */
public final class SequentialIdGenerator implements IdGenerator {

  private final String prefix;
  private final AtomicLong next = new AtomicLong();

  public SequentialIdGenerator(String prefix) {
    this.prefix = prefix;
  }

  @Override
  public String newId() {
    return prefix + "-" + next.incrementAndGet();
  }
}
