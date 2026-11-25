package io.github.federiconbaez.banking.support;

import io.github.federiconbaez.banking.domain.DomainError;
import io.github.federiconbaez.banking.domain.Result;
import java.time.Instant;

public final class Fixtures {
  public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private Fixtures() {}

  public static <T> T ok(Result<T> result) {
    if (result instanceof Result.Ok<T> ok) {
      return ok.value();
    }
    throw new AssertionError("expected Ok but was " + result);
  }

  public static DomainError err(Result<?> result) {
    if (result instanceof Result.Err<?> err) {
      return err.error();
    }
    throw new AssertionError("expected Err but was " + result);
  }
}
