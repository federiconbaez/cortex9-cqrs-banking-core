package io.github.federiconbaez.banking.application.port;

import java.time.Instant;

/** Puerto de tiempo: el dominio nunca lee el reloj del sistema. */
public interface Clock {
  Instant now();
}
