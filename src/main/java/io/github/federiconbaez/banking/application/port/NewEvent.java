package io.github.federiconbaez.banking.application.port;

import io.github.federiconbaez.banking.domain.AccountEvent;

/** Evento a persistir con su id unico. */
public record NewEvent(String eventId, AccountEvent event) {}
