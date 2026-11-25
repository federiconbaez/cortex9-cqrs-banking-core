package io.github.federiconbaez.banking.application.port;

import io.github.federiconbaez.banking.application.Command;
import io.github.federiconbaez.banking.application.CommandResult;

/** Puerto de entrada (driving port) para despachar comandos. */
public interface CommandGateway {
  CommandResult dispatch(Command command);
}
