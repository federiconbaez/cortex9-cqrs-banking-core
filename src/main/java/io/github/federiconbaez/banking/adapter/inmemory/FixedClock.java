package io.github.federiconbaez.banking.adapter.inmemory;

import io.github.federiconbaez.banking.application.port.Clock;
import java.time.Duration;
import java.time.Instant;

/** Reloj controlable para tests y simulaciones deterministas. */
public final class FixedClock implements Clock {

  private Instant now;

  public FixedClock(Instant start) {
    this.now = start;
  }

  @Override
  public synchronized Instant now() {
    return now;
  }

  public synchronized void advance(Duration duration) {
    now = now.plus(duration);
  }
}
