package io.github.federiconbaez.banking.domain;

import java.util.Objects;

/**
 * Value object monetario: unidades menores en {@code long} + moneda. Nunca usa double/float.
 *
 * <p>Las violaciones de precondicion (moneda nula, mezclar monedas en {@link #plus}) son errores de
 * programacion y lanzan excepcion; las reglas de negocio se modelan como {@link DomainError}.
 */
public record Money(long minorUnits, Currency currency) {

  public Money {
    Objects.requireNonNull(currency, "currency");
  }

  public static Money of(long minorUnits, Currency currency) {
    return new Money(minorUnits, currency);
  }

  public static Money zero(Currency currency) {
    return new Money(0, currency);
  }

  public boolean isPositive() {
    return minorUnits > 0;
  }

  /** Suma exacta; lanza {@link ArithmeticException} ante overflow. */
  public Money plus(Money other) {
    if (other.currency != currency) {
      throw new IllegalArgumentException(
          "currency mismatch: " + currency + " vs " + other.currency);
    }
    return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
  }

  public Money negate() {
    return new Money(Math.negateExact(minorUnits), currency);
  }
}
