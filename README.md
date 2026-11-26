# cortex9-cqrs-banking-core

Núcleo bancario **CQRS + Event Sourcing** en **Java 21**, arquitectura hexagonal, sin frameworks.
Proyecto de estudio y portfolio (CX9-6202, CX9-6205): no es un sistema bancario productivo.

## Qué hace

- **Agregado `Account`**: abrir, depositar, extraer, congelar/descongelar y cerrar. El estado sale
  solo de eventos (`AccountEvent`, records sellados).
- **Dinero sin `double`**: `Money` = `long` en unidades menores + `Currency`. Overflow y mezcla de
  monedas se detectan.
- **Errores tipados**: `DomainError` es una `sealed interface`; las decisiones devuelven
  `Result<AccountEvent>`. Nada de excepciones genéricas para reglas de negocio (ADR-0001).
- **Lado Command**: `CommandBus` con concurrencia optimista (expected version), reintento acotado e
  idempotencia por `CommandId`, con respaldo por `causationId` en el stream (ADR-0002).
- **Lado Query**: `ProjectionRunner` lee el log global por lotes y alimenta read models de saldos y
  extracto (consistencia eventual, tolera reentregas).
- **Saga de transferencia**: débito → crédito → compensación si el crédito se rechaza. Pasos
  idempotentes, se puede reejecutar con el mismo id.
- **Adapters en memoria**: EventStore, idempotencia, read models, reloj fijo/sistema, ids
  secuenciales/UUID.
- **Event Store durable en SQLite** (ADR-0003): `SqliteEventStore` con `BEGIN IMMEDIATE`,
  `UNIQUE(stream_id, version)`, posiciones globales `AUTOINCREMENT` y esquema versionado. Pasa la
  misma `EventStoreContract` que el fake. Una propiedad diferencial exige que, ante la misma
  secuencia aleatoria de comandos, SQLite y memoria den resultados y logs idénticos.

## Qué NO hace

- Solo el Event Store es durable (SQLite). El store de idempotencia y los read models viven en
  memoria. Los read models se reconstruyen desde el log, y los comandos aceptados se deduplican
  por `causationId` aunque se pierda el store de idempotencia.
- SQLite serializa a los escritores: sirve para un nodo, no para varios hosts. No hay adapter
  Postgres.
- No expone HTTP; no hay mensajería real.
- No convierte monedas ni calcula intereses o comisiones.
- No hay snapshots ni versionado de eventos. Para tener snapshots habría que sumar un índice de
  causación, porque el bus recorre la historia completa para deduplicar (ADR-0003).
- La saga no persiste su propio estado: se reanuda reejecutándola con el mismo `transferId`.
- `CompensationFailed` (crédito y reembolso rechazados) requiere intervención manual.

Detalle completo en [CONTRACT.md](CONTRACT.md) y en [docs/adr](docs/adr).

## Estructura

```
domain/        Account, AggregateRoot, Money, AccountEvent, DomainError, Result (solo JDK + jspecify)
application/   Command, CommandBus, AccountRepository, CommandResult
  port/        EventStore, IdempotencyStore, Clock, IdGenerator, CommandGateway, Projection
  saga/        TransferSaga, TransferOutcome
  projection/  ProjectionRunner
adapter/inmemory/  InMemoryEventStore, InMemoryIdempotencyStore, read models, clocks, ids
adapter/sqlite/    SqliteEventStore, EventRows (evento <-> columnas), StorageException
```

## Calidad (números medidos en local con JDK Temurin 21.0.13 y en CI)

| Gate | Resultado |
|---|---|
| Tests (JUnit 5 + jqwik + ArchUnit) | **106 tests**, 0 fallas |
| Propiedades jqwik | 6 propiedades, 1.650 casos generados por corrida (100 diferenciales SQLite vs memoria) |
| Cobertura de ramas del dominio (JaCoCo) | **68/68 = 100 %** (el build falla si baja de 95 %) |
| Cobertura de ramas del adapter SQLite (JaCoCo) | 36/39 = 92,3 % (informativo) |
| Mutation testing PIT (dominio) | **80/81 = 98,8 %** (umbral 80 %) |
| Mutation testing PIT (adapter SQLite) | **61/68 = 89,7 %** (umbral 80 % en CI) |
| Arquitectura | 7 reglas ArchUnit (incluye `java.sql` solo en `adapter.sqlite`) |
| Error Prone + NullAway | activos en código de producción; build falla ante error de nulidad |
| Formato | Spotless + google-java-format (check en `verify`) |
| Secretos | gitleaks en CI |

El mutante sobreviviente del dominio es equivalente: cambiar `>` por `>=` en el cálculo saturado
de `availableMinor()` da el mismo valor en el borde (`Long.MAX_VALUE`).

En el adapter SQLite sobreviven 5 mutantes y hay 2 sin cobertura. Son equivalentes o defensivos:
- `setNull` sobre un parámetro que, si no se liga, ya vale NULL.
- `Boolean.FALSE` en una lambda cuyo valor no se usa.
- El chequeo de "no volvió la clave generada", que no ocurre con SQLite.
- El rollback tras un `COMMIT` fallido, que no sé provocar de forma determinista.

Tests sin Mockito: los fakes están escritos a mano (`ConflictInjectingEventStore`,
`ForgetfulIdempotencyStore`, `ScriptedGateway`). La suite `EventStoreContract` (10 casos) corre
contra el fake y contra SQLite. `SqliteDurabilityTest` cubre lo que solo una base real puede
probar:
- los eventos sobreviven a cerrar y reabrir;
- un reinicio que pierde el store de idempotencia no duplica el depósito reenviado, y las
  proyecciones se reconstruyen desde disco;
- dos conexiones al mismo archivo (50 rondas, 4 escritores) tienen exactamente un ganador por
  versión;
- la base rechaza una versión duplicada aunque se escriba por fuera del adapter;
- las filas corruptas dan `StorageException`;
- un error a mitad de lote hace rollback de todo;
- un esquema más nuevo hace que el adapter se niegue a abrir.

Propiedades verificadas:
1. El saldo nunca baja de `-limiteDescubierto`.
2. El replay de la historia es determinista y coincide con el estado en vivo.
3. Las transferencias (incluidas compensaciones y cuentas congeladas/cerradas) conservan el total.
4. Despachar dos veces el mismo comando da el mismo resultado y no agrega eventos.
5. Diferencial: SQLite y memoria dan los mismos resultados y el mismo log ante la misma secuencia
   de comandos, y el log sigue igual tras reabrir.

## Cómo correrlo

```bash
mvn verify                                        # compila con Error Prone/NullAway, tests, JaCoCo, Spotless
mvn org.pitest:pitest-maven:mutationCoverage      # PIT del dominio, reporte en target/pit-reports
mvn org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses='io.github.federiconbaez.banking.adapter.sqlite.*' \
  -DexcludedTestClasses='*ArchitectureTest'       # PIT del adapter SQLite
mvn spotless:apply                                # formatear
```

Requiere JDK 21+ y Maven 3.9+. `.mvn/maven.config` agrega reintentos ante 429/503 de Maven Central.

Uso del Event Store durable:

```java
try (var store = SqliteEventStore.open(Path.of("bank.db"))) {
  var bus = new CommandBus(store, new InMemoryIdempotencyStore(), clock, ids, 3);
  bus.dispatch(command);
}
```

## Cómo contarlo en una entrevista

- **El problema**: un core bancario tiene que ser auditable, no perder ni duplicar plata y soportar
  reintentos y concurrencia.
- **La decisión central**: event sourcing. El saldo no se guarda, se deriva de eventos inmutables;
  la auditoría sale gratis y el replay es determinista (lo pruebo con propiedades).
- **Concurrencia**: optimista, con expected version por stream. Un test con 8 hilos y 400 depósitos
  concurrentes termina con el saldo exacto.
- **Idempotencia**: dos niveles. Store de idempotencia más `causationId` en cada evento, para que un
  crash entre el append y el registro no duplique un depósito.
- **Transferencias**: no hay transacción entre dos agregados, así que uso una saga con compensación.
  El detalle fino: solo compensa ante un rechazo definitivo; ante un conflicto transitorio devuelve
  `Pending`, porque compensar y después acreditar en un reintento crearía dinero.
- **Errores como tipos**: `sealed interface` + `switch` exhaustivo; agregar un error nuevo rompe la
  compilación donde no se maneja.
- **Cómo sé que los tests sirven**: mutation testing (98,8 % en dominio) y no solo cobertura.
- **Qué haría después**: el adapter SQLite ya pasa la suite de contrato y la prueba diferencial. El
  siguiente paso es PostgreSQL con la misma `UNIQUE(stream_id, version)` y la misma suite, un
  outbox para publicar eventos y snapshots con índice de causación si los streams crecen.

## Licencia

Apache-2.0. Ver [LICENSE](LICENSE).
