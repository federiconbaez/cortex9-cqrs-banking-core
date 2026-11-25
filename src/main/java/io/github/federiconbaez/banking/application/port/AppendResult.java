package io.github.federiconbaez.banking.application.port;

import java.util.List;

/** Resultado de un append con concurrencia optimista. */
public sealed interface AppendResult {

  record Appended(List<StoredEvent> events) implements AppendResult {
    public Appended {
      events = List.copyOf(events);
    }
  }

  record VersionConflict(long expectedVersion, long actualVersion) implements AppendResult {}
}
