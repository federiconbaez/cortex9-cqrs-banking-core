# ADR-0003: Event Store durable sobre SQLite con la misma suite de contrato

Estado: aceptada (CX9-6205)

## Contexto
`EventStoreContract` existía "para cualquier adapter futuro", pero solo lo corría el fake en
memoria. Además, ADR-0002 promete que un reintento no duplica un depósito aunque se pierda el
store de idempotencia, gracias al `causationId` guardado en cada evento. Esa promesa solo tiene
sentido si el log sobrevive al proceso, y no estaba probada con un log durable.

## Decisión
- Nuevo paquete `adapter.sqlite` con `SqliteEventStore` (`EventStore`), usando `sqlite-jdbc` como
  dependencia de runtime.
- `append` corre en una transacción `BEGIN IMMEDIATE`: lee la versión del stream, compara con la
  esperada y escribe todo el lote o nada. `UNIQUE(stream_id, version)` es la segunda línea de
  defensa a nivel de base. `global_position` es `AUTOINCREMENT`, así que es creciente y sin huecos
  ante rollbacks.
- Los eventos se guardan en columnas por campo con un `switch` exhaustivo sobre el `sealed
  interface`: no hay serializador genérico ni reflexión. Un evento nuevo no compila hasta que se
  mapea.
- `EventStoreContract` pasa a `adapter.contract` (tests) y corre contra los dos adapters.
- Una propiedad diferencial ejecuta la misma secuencia aleatoria de comandos contra el bus sobre
  SQLite y sobre memoria, y exige resultados y log global idénticos, también después de reabrir.
- `java.sql` y `org.sqlite` solo pueden aparecer en `adapter.sqlite` (regla ArchUnit). Las fallas
  de infraestructura son `StorageException`.
- Esquema versionado con `PRAGMA user_version` (versión 1). Si la versión en disco es más nueva, el
  adapter se niega a abrir.

## Consecuencias
- Queda probado que tras un reinicio que pierde el store de idempotencia (en memoria), reenviar
  el mismo depósito devuelve `Accepted` con la versión original y no agrega eventos.
- El store de idempotencia sigue siendo solo en memoria. Persistirlo exigiría serializar
  `Command` y `CommandResult`. Con el log durable, el respaldo por `causationId` ya cubre los
  depósitos, extracciones y demás comandos aceptados. Al perder el store se pierden dos cosas: el
  cacheo de rechazos, que se recalculan contra el estado actual, y la detección de
  `IdempotencyKeyReused` para comandos anteriores al reinicio.
- SQLite serializa a los escritores: sirve para un nodo, o para pocos procesos en la misma
  máquina. No sirve para varios hosts ni para un sistema de archivos de red.
- No hay snapshots. El bus necesita la historia completa del stream para buscar el
  `causationId`. Un snapshot exigiría también un índice de causación (por ejemplo, una tabla
  `(stream_id, causation_id)`), y queda como trabajo futuro.
