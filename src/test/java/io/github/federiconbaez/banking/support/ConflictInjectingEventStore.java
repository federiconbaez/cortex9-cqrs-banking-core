package io.github.federiconbaez.banking.support;

import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.port.AppendResult;
import io.github.federiconbaez.banking.application.port.EventStore;
import io.github.federiconbaez.banking.application.port.NewEvent;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import java.util.List;

/** Fake: simula escritores concurrentes devolviendo VersionConflict en los primeros N appends. */
public final class ConflictInjectingEventStore implements EventStore {
  private final EventStore delegate;
  private int conflictsLeft;
  public int appendCalls;

  public ConflictInjectingEventStore(EventStore delegate, int conflicts) {
    this.delegate = delegate;
    this.conflictsLeft = conflicts;
  }

  public void injectConflicts(int conflicts) {
    this.conflictsLeft = conflicts;
  }

  @Override
  public List<StoredEvent> load(String streamId) {
    return delegate.load(streamId);
  }

  @Override
  public AppendResult append(
      String streamId, long expectedVersion, CommandId causationId, List<NewEvent> events) {
    appendCalls++;
    if (conflictsLeft > 0) {
      conflictsLeft--;
      return new AppendResult.VersionConflict(expectedVersion, expectedVersion + 1);
    }
    return delegate.append(streamId, expectedVersion, causationId, events);
  }

  @Override
  public List<StoredEvent> readAll(long afterGlobalPosition, int maxCount) {
    return delegate.readAll(afterGlobalPosition, maxCount);
  }
}
