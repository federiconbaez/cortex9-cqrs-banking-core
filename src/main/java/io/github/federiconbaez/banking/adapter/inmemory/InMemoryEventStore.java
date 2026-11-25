package io.github.federiconbaez.banking.adapter.inmemory;

import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.port.AppendResult;
import io.github.federiconbaez.banking.application.port.EventStore;
import io.github.federiconbaez.banking.application.port.NewEvent;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Event Store en memoria, thread-safe por monitor unico. Sin durabilidad. */
public final class InMemoryEventStore implements EventStore {

  private final Map<String, List<StoredEvent>> streams = new HashMap<>();
  private final List<StoredEvent> log = new ArrayList<>();

  @Override
  public synchronized List<StoredEvent> load(String streamId) {
    return List.copyOf(streams.getOrDefault(streamId, List.of()));
  }

  @Override
  public synchronized AppendResult append(
      String streamId, long expectedVersion, CommandId causationId, List<NewEvent> events) {
    if (events.isEmpty()) {
      throw new IllegalArgumentException("events must not be empty");
    }
    List<StoredEvent> stream = streams.getOrDefault(streamId, List.of());
    long actual = stream.size();
    if (actual != expectedVersion) {
      return new AppendResult.VersionConflict(expectedVersion, actual);
    }
    List<StoredEvent> target = streams.computeIfAbsent(streamId, k -> new ArrayList<>());
    List<StoredEvent> stored = new ArrayList<>(events.size());
    long version = actual;
    for (NewEvent event : events) {
      version++;
      StoredEvent s =
          new StoredEvent(
              event.eventId(), streamId, version, log.size() + 1L, causationId, event.event());
      target.add(s);
      log.add(s);
      stored.add(s);
    }
    return new AppendResult.Appended(stored);
  }

  @Override
  public synchronized List<StoredEvent> readAll(long afterGlobalPosition, int maxCount) {
    if (maxCount < 1) {
      throw new IllegalArgumentException("maxCount must be >= 1");
    }
    int from = (int) Math.min(Math.max(afterGlobalPosition, 0), log.size());
    int to = (int) Math.min((long) from + maxCount, log.size());
    return List.copyOf(log.subList(from, to));
  }
}
