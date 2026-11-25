package io.github.federiconbaez.banking.application.port;

import io.github.federiconbaez.banking.application.Command;
import io.github.federiconbaez.banking.application.CommandResult;

/** Comando original + su resultado definitivo. */
public record IdempotencyRecord(Command command, CommandResult result) {}
