package io.github.federiconbaez.banking.adapter.inmemory;

import io.github.federiconbaez.banking.application.port.EventStore;

class InMemoryEventStoreContractTest extends EventStoreContract {
  @Override
  protected EventStore newStore() {
    return new InMemoryEventStore();
  }
}
