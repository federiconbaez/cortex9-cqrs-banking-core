package io.github.federiconbaez.banking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void requiresCurrency() {
    assertThatThrownBy(() -> Money.of(1, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void zeroAndPositivity() {
    assertThat(Money.zero(Currency.ARS)).isEqualTo(Money.of(0, Currency.ARS));
    assertThat(Money.of(0, Currency.ARS).isPositive()).isFalse();
    assertThat(Money.of(1, Currency.ARS).isPositive()).isTrue();
    assertThat(Money.of(-1, Currency.ARS).isPositive()).isFalse();
  }

  @Test
  void plusAddsSameCurrency() {
    assertThat(Money.of(150, Currency.USD).plus(Money.of(-50, Currency.USD)))
        .isEqualTo(Money.of(100, Currency.USD));
  }

  @Test
  void plusRejectsMixedCurrencies() {
    assertThatThrownBy(() -> Money.of(1, Currency.USD).plus(Money.of(1, Currency.EUR)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void plusDetectsOverflow() {
    assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, Currency.USD).plus(Money.of(1, Currency.USD)))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void negate() {
    assertThat(Money.of(7, Currency.EUR).negate()).isEqualTo(Money.of(-7, Currency.EUR));
    assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, Currency.EUR).negate())
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void accountIdValidation() {
    assertThatThrownBy(() -> new AccountId(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccountId(null)).isInstanceOf(NullPointerException.class);
    assertThat(new AccountId("a").value()).isEqualTo("a");
  }

  @Test
  void resultHelpers() {
    assertThat(Result.ok(1).isOk()).isTrue();
    assertThat(Result.err(new DomainError.InvalidAmount(0)).isOk()).isFalse();
  }
}
