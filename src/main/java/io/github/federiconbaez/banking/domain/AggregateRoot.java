package io.github.federiconbaez.banking.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Base del patron Aggregate con event sourcing. El estado solo cambia aplicando eventos; {@link
 * #version()} es la cantidad de eventos ya persistidos (usada como expected version).
 */
public abstract class AggregateRoot<E> {

  private long version;
  private final List<E> uncommitted = new ArrayList<>();

  /** Transicion pura de estado: sin validaciones ni efectos (debe aceptar cualquier historia). */
  protected abstract void when(E event);

  protected final E raise(E event) {
    when(event);
    uncommitted.add(event);
    return event;
  }

  protected final void replay(List<? extends E> history) {
    for (E event : history) {
      when(event);
      version++;
    }
  }

  public final long version() {
    return version;
  }

  public final List<E> uncommittedEvents() {
    return List.copyOf(uncommitted);
  }

  public final void markCommitted() {
    version += uncommitted.size();
    uncommitted.clear();
  }
}
