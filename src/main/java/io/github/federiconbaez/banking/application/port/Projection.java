package io.github.federiconbaez.banking.application.port;

/** Read model alimentado por eventos (lado Query de CQRS). */
public interface Projection {
  void on(StoredEvent event);
}
