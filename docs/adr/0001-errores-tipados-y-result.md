# ADR-0001: Errores de negocio como tipos sellados, no excepciones

Estado: aceptada

## Contexto
Las reglas bancarias fallan de formas esperables (fondos insuficientes, cuenta congelada). Con
excepciones el llamador no sabe qué casos manejar y es fácil olvidar uno.

## Decisión
Las decisiones del agregado devuelven `Result<AccountEvent>` (`Ok` | `Err(DomainError)`), y
`DomainError` es una `sealed interface` de records. El `CommandBus` traduce a `CommandResult`
(también sellado) y la saga a `TransferOutcome`. Los `switch` sobre estos tipos son exhaustivos y
los verifica el compilador.

## Consecuencias
- Agregar un error nuevo rompe la compilación de los `switch` que no lo manejan: es intencional.
- Las excepciones quedan para errores de programación (null, id vacío, overflow en `Money.plus`).
- Los rechazos son datos: se pueden cachear por idempotencia y comparar en tests.
