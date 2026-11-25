package io.github.federiconbaez.banking.application.projection;

import io.github.federiconbaez.banking.application.port.EventStore;
import io.github.federiconbaez.banking.application.port.Projection;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import java.util.List;

/**
 * Alimenta los read models leyendo el log global por lotes desde un checkpoint. Entrega
 * at-least-once dentro del proceso; consistencia eventual respecto del lado Command.
 */
public final class ProjectionRunner {

  private final EventStore store;
  private final List<Projection> projections;
  private final int batchSize;
  private long checkpoint;

  public ProjectionRunner(EventStore store, List<Projection> projections, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1");
    }
    this.store = store;
    this.projections = List.copyOf(projections);
    this.batchSize = batchSize;
  }

  /** Procesa todo lo pendiente; devuelve cuantos eventos aplico. */
  public synchronized int catchUp() {
    int applied = 0;
    List<StoredEvent> batch = store.readAll(checkpoint, batchSize);
    while (!batch.isEmpty()) {
      for (StoredEvent event : batch) {
        for (Projection projection : projections) {
          projection.on(event);
        }
        checkpoint = event.globalPosition();
        applied++;
      }
      batch = store.readAll(checkpoint, batchSize);
    }
    return applied;
  }

  public synchronized long checkpoint() {
    return checkpoint;
  }
}
