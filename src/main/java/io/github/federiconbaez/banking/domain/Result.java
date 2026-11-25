package io.github.federiconbaez.banking.domain;

/** Resultado de una decision del dominio: exito con valor o error de negocio tipado. */
public sealed interface Result<T> {

  record Ok<T>(T value) implements Result<T> {}

  record Err<T>(DomainError error) implements Result<T> {}

  static <T> Result<T> ok(T value) {
    return new Ok<>(value);
  }

  static <T> Result<T> err(DomainError error) {
    return new Err<>(error);
  }

  default boolean isOk() {
    return this instanceof Ok<T>;
  }
}
