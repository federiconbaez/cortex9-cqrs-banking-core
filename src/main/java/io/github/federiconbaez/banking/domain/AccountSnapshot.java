package io.github.federiconbaez.banking.domain;

import org.jspecify.annotations.Nullable;

/** Foto inmutable del estado del agregado (para tests de determinismo y consultas puntuales). */
public record AccountSnapshot(
    AccountId accountId,
    AccountStatus status,
    @Nullable Currency currency,
    @Nullable String owner,
    long balanceMinor,
    long overdraftLimitMinor,
    long version) {}
