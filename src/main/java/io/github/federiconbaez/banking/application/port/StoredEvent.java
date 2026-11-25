package io.github.federiconbaez.banking.application.port;

import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.domain.AccountEvent;

/**
 * Evento persistido. {@code version} es 1-based dentro del stream; {@code globalPosition} es
 * 1-based y estrictamente creciente en todo el store; {@code causationId} es el comando que lo
 * produjo (permite dedupe aunque se pierda el store de idempotencia).
 */
public record StoredEvent(
    String eventId,
    String streamId,
    long version,
    long globalPosition,
    CommandId causationId,
    AccountEvent event) {}
