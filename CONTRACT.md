# CONTRACT — cortex9-cqrs-banking-core (CX9-6202)

## Alcance

Núcleo bancario CQRS + Event Sourcing en Java 21, arquitectura hexagonal, sin frameworks:

- Agregado `Account` (abrir, depositar, extraer, congelar/descongelar, cerrar) cuyo estado se deriva
  solo de eventos.
- Lado Command: `CommandBus` con concurrencia optimista (expected version), reintento acotado e
  idempotencia por `CommandId`.
- Lado Query: proyecciones de saldos y extracto alimentadas desde el log global (`ProjectionRunner`).
- Saga orquestada de transferencia: débito → crédito → compensación si el crédito es rechazado.
- Adapters en memoria: EventStore, store de idempotencia, read models, reloj fijo/sistema, ids
  secuenciales/UUID.
- Adapter durable SQLite del `EventStore` (CX9-6205, ADR-0003). Todo adapter de `EventStore` debe
  pasar `EventStoreContract`; hoy la corren memoria y SQLite.

## Fuera de alcance (non-goals)

- Persistencia durable del store de idempotencia y de los read models. Base servidor
  (Postgres), varios hosts. Red, HTTP, mensajería, autenticación, multi-tenant.
- Conversión de monedas, intereses, comisiones, límites diarios.
- Snapshots de agregados, upcasting/versionado de eventos.
- Atomicidad entre el append y el store de idempotencia en un backend real (ver ADR-0002).
- Saga distribuida con persistencia de estado propio: la saga se reanuda por idempotencia de pasos.

## Invariantes

1. `saldo >= -limiteDescubierto` siempre (límite por defecto 0 ⇒ nunca negativo).
2. Montos en `long` de unidades menores + `Currency`; nunca `double`/`float`. Overflow ⇒ error tipado.
3. Una moneda por cuenta, fijada al abrir. Montos de otra moneda ⇒ `CurrencyMismatch`.
4. Montos de movimientos estrictamente positivos.
5. `FROZEN` admite créditos y rechaza débitos. `CLOSED` es terminal. Cerrar exige saldo exacto 0.
6. Replay determinista: `rehydrate(history)` produce el mismo estado que la ejecución en vivo.
7. Versión del stream = cantidad de eventos; el append falla si la versión esperada no coincide.
   En SQLite lo respalda además `UNIQUE(stream_id, version)`. Las posiciones globales son
   crecientes y un conflicto no consume posiciones.
8. Mismo `CommandId` + mismo payload ⇒ mismo `CommandResult` y ningún evento nuevo.
9. Una transferencia terminada (`Completed`, `DebitRejected`, `Compensated`) conserva la suma total
   de saldos.

## Errores (sealed, sin excepciones genéricas para reglas de negocio)

`DomainError`: `AccountNotOpened`, `AccountAlreadyOpened`, `AccountIsClosed`, `AccountIsFrozen`,
`AccountNotFrozen`, `InvalidOwner`, `InvalidOverdraftLimit`, `InvalidAmount`, `CurrencyMismatch`,
`InsufficientFunds`, `BalanceOverflow`, `NonZeroBalance`.

`CommandResult`: `Accepted`, `Rejected(DomainError)`, `ConcurrencyConflict` (transitorio, no se
cachea), `IdempotencyKeyReused`.

`TransferOutcome`: `Completed`, `DebitRejected`, `Compensated`, `CompensationFailed` (intervención
manual), `Pending` (transitorio, reintentar con el mismo id), `Aborted`, `InvalidTransfer`.

Violaciones de precondición de value objects (id vacío, moneda nula, mezclar monedas en
`Money.plus`) son errores de programación y lanzan excepciones estándar.

## Puertos

| Puerto | Tipo | Responsabilidad |
|---|---|---|
| `CommandGateway` | entrada | despachar comandos |
| `EventStore` | salida | `load`, `append` con expected version, `readAll` paginado (memoria y SQLite) |
| `IdempotencyStore` | salida | `find`/`save` first-writer-wins |
| `Clock` | salida | tiempo inyectado |
| `IdGenerator` | salida | ids de evento |
| `Projection` | salida | read model alimentado por eventos |

## Patrones explícitos

Aggregate (`AggregateRoot`, `Account`), Command (`Command`), Event (`AccountEvent`), Repository
(`AccountRepository`), Saga (`TransferSaga`), CQRS (`CommandBus` / `ProjectionRunner`).

## Criterios de aceptación

- [x] Dominio sin dependencias de application/adapters/frameworks (ArchUnit).
- [x] Tests JUnit 5 con fakes escritos a mano (sin Mockito).
- [x] Propiedades jqwik: saldo respeta política, replay determinista, transferencias conservan el
      total, comando idempotente = mismo resultado.
- [x] Suite de contrato de `EventStore` corrida contra memoria y SQLite; propiedad diferencial.
- [x] PIT sobre el adapter SQLite ≥ 80 % en CI.
- [x] PIT sobre dominio con umbral declarado ≥ 80 %.
- [x] JaCoCo: cobertura de ramas del dominio ≥ 95 % (falla el build si baja).
- [x] Error Prone + NullAway en código de producción; Spotless (google-java-format).
- [x] gitleaks en CI; CI verde en `main`.
