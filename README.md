# cortex9-cqrs-banking-core

Núcleo bancario **CQRS + Event Sourcing** en **Java 21**, arquitectura hexagonal, sin frameworks.
Proyecto de estudio y portfolio (CX9-6202): no es un sistema bancario productivo.

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

## Qué NO hace

- No persiste en disco ni base de datos; no expone HTTP; no hay mensajería real.
- No convierte monedas ni calcula intereses o comisiones.
- No hay snapshots ni versionado de eventos.
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
```

## Calidad (números medidos en local con JDK Temurin 21.0.13 y en CI)

| Gate | Resultado |
|---|---|
| Tests (JUnit 5 + jqwik + ArchUnit) | **82 tests**, 0 fallas |
| Propiedades jqwik | 5 propiedades, 1.550 casos generados por corrida |
| Cobertura de ramas del dominio (JaCoCo) | **68/68 = 100 %** (el build falla si baja de 95 %) |
| Mutation testing PIT (dominio) | **80/81 = 98,8 %** (umbral 80 %) |
| Error Prone + NullAway | activos en código de producción; build falla ante error de nulidad |
| Formato | Spotless + google-java-format (check en `verify`) |
| Secretos | gitleaks en CI |

El mutante sobreviviente es equivalente: cambiar `>` por `>=` en el cálculo saturado de
`availableMinor()` da el mismo valor en el borde (`Long.MAX_VALUE`).

Tests sin Mockito: los fakes están escritos a mano (`ConflictInjectingEventStore`,
`ForgetfulIdempotencyStore`, `ScriptedGateway`). La suite `EventStoreContract` es reutilizable para
cualquier adapter futuro de EventStore.

Propiedades verificadas:
1. El saldo nunca baja de `-limiteDescubierto`.
2. El replay de la historia es determinista y coincide con el estado en vivo.
3. Las transferencias (incluidas compensaciones y cuentas congeladas/cerradas) conservan el total.
4. Despachar dos veces el mismo comando da el mismo resultado y no agrega eventos.

## Cómo correrlo

```bash
mvn verify                                        # compila con Error Prone/NullAway, tests, JaCoCo, Spotless
mvn org.pitest:pitest-maven:mutationCoverage      # PIT, reporte en target/pit-reports
mvn spotless:apply                                # formatear
```

Requiere JDK 21+ y Maven 3.9+.

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
- **Qué haría después**: adapter durable (PostgreSQL con `UNIQUE(stream_id, version)`) que pase la
  misma suite de contrato, outbox para publicar eventos y snapshots si los streams crecen.

## Licencia

Apache-2.0. Ver [LICENSE](LICENSE).
