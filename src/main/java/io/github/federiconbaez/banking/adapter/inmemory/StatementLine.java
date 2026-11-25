package io.github.federiconbaez.banking.adapter.inmemory;

import java.time.Instant;

/** Linea de extracto: monto con signo y saldo resultante, en unidades menores. */
public record StatementLine(
    long version,
    Instant occurredAt,
    String kind,
    long signedAmountMinor,
    long balanceAfterMinor,
    String reference) {}
