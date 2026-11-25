package io.github.federiconbaez.banking.application.port;

import io.github.federiconbaez.banking.application.CommandId;
import java.util.List;

/**
 * Puerto del Event Store. Contrato (verificado por {@code EventStoreContract}):
 *
 * <ul>
 *   <li>{@code append} es atomico: o se agregan todos los eventos o ninguno;
 *   <li>si la version actual del stream != {@code expectedVersion} devuelve {@link
 *       AppendResult.VersionConflict} sin escribir;
 *   <li>{@code events} vacio es un error de programacion ({@link IllegalArgumentException});
 *   <li>{@code load} devuelve el stream completo en orden de version (vacio si no existe);
 *   <li>{@code readAll(after, max)} devuelve hasta {@code max} eventos con posicion global &gt;
 *       {@code after}, en orden.
 * </ul>
 */
public interface EventStore {

  List<StoredEvent> load(String streamId);

  AppendResult append(
      String streamId, long expectedVersion, CommandId causationId, List<NewEvent> events);

  List<StoredEvent> readAll(long afterGlobalPosition, int maxCount);
}
