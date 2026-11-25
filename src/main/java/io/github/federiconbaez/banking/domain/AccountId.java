package io.github.federiconbaez.banking.domain;

import java.util.Objects;

/** Identidad de la cuenta (y de su stream de eventos). */
public record AccountId(String value) {

  public AccountId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("account id must not be blank");
    }
  }
}
