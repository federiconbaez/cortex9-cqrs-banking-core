package io.github.federiconbaez.banking.application;

import java.util.Objects;

/** Clave de idempotencia: el cliente la elige; reintentar con la misma clave es seguro. */
public record CommandId(String value) {

  public CommandId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("command id must not be blank");
    }
  }
}
