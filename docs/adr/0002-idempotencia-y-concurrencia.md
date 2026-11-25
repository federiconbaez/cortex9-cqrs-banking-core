# ADR-0002: Idempotencia en dos niveles y concurrencia optimista

Estado: aceptada

## Contexto
Un cliente puede reintentar un comando (timeout, doble click). Dos escritores pueden modificar la
misma cuenta a la vez. En memoria no hay transacción que cubra el append y el registro de
idempotencia juntos.

## Decisión
1. `IdempotencyStore` (first-writer-wins) guarda comando + resultado definitivo (`Accepted` o
   `Rejected`). Misma clave con otro payload ⇒ `IdempotencyKeyReused`.
2. Respaldo: cada evento guarda `causationId`. Si el store de idempotencia perdió el registro (crash
   entre append y save), el bus encuentra los eventos causados por ese comando en el stream y
   reconstruye el mismo `Accepted` sin volver a escribir.
3. `append` usa expected version; ante conflicto el bus recarga y reintenta hasta `maxAttempts`.
   Agotado, devuelve `ConcurrencyConflict`, que no se cachea (el cliente puede reintentar).
4. La saga deriva claves por paso (`<id>:debit`, `:credit`, `:compensate`) y solo compensa ante un
   rechazo definitivo; ante conflicto transitorio devuelve `Pending`.

## Consecuencias
- Reejecutar una saga es seguro y reanuda donde quedó.
- El respaldo por `causationId` cubre comandos aceptados; un rechazo perdido se reevalúa y podría
  dar otro resultado si el estado cambió (aceptable: no produjo efectos).
- Con un backend real, idempotencia y append deberían compartir transacción (fuera de alcance).
