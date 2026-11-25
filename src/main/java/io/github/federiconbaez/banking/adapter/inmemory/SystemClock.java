package io.github.federiconbaez.banking.adapter.inmemory;

import io.github.federiconbaez.banking.application.port.Clock;
import java.time.Instant;

/** Reloj de sistema (UTC). */
public final class SystemClock implements Clock {
  @Override
  public Instant now() {
    return Instant.now();
  }
}
