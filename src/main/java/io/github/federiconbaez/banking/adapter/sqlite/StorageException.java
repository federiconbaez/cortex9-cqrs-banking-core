package io.github.federiconbaez.banking.adapter.sqlite;

/**
 * Falla de infraestructura (I/O, base bloqueada más allá del timeout, esquema inválido, fila
 * corrupta). No es un error de negocio: esos siguen siendo {@code DomainError}, y el conflicto de
 * versión sigue siendo {@code AppendResult.VersionConflict}.
 */
public final class StorageException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }

  public StorageException(String message) {
    super(message);
  }
}
