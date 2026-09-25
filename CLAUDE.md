# Billetera — Reto técnico HabiCapital

Sistema para mover plata entre personas: crear cuenta, cargar saldo (simulado),
transferir, consultar saldo, ver historial. Feature extra: **cobros divididos**
(ej. "La cena": alguien pagó y le cobra a N personas y ve quién ya pagó).

**Regla que no se negocia: el sistema no puede perder ni crear un peso.**

## Stack
- Java 21, Spring Boot, Maven (`./mvnw`, en Windows `mvnw.cmd`)
- PostgreSQL (Docker Compose en dev, Testcontainers en tests)
- Flyway para migraciones (`src/main/resources/db/migration`)
- Acceso a datos con `JdbcClient` y SQL explícito. **No usar JPA/Hibernate.**

## Invariantes (nunca romperlas)
1. Montos siempre en centavos como `long`. Nunca `double`/`float`.
2. El saldo solo cambia a través de asientos en `ledger_entries`. Cada
   transacción genera asientos que suman exactamente 0 (doble entrada).
   El saldo cacheado en `accounts.balance_cents` se actualiza en la misma
   transacción de BD que el asiento.
3. `ledger_entries` es inmutable: nunca UPDATE ni DELETE. Los errores se
   corrigen con asientos compensatorios.
4. Toda operación que mueve plata corre en UNA transacción de BD y bloquea
   las cuentas con `SELECT ... FOR UPDATE` **en orden de id** (evita deadlocks).
5. Validar fondos **después** de bloquear, nunca antes.
6. Toda operación que mueve plata recibe un `Idempotency-Key`. Reintentar con
   la misma llave y el mismo payload devuelve el resultado original sin mover
   plata de nuevo; misma llave con otro payload → 409.
7. Las cuentas `SYSTEM` (fondeo externo) son las únicas que pueden quedar
   negativas y no se pueden usar desde la API como origen/destino de transferencias.

## Decisiones del feature de cobros divididos
- El residuo del redondeo se reparte de a 1 centavo entre los primeros
  deudores; la suma de cuotas siempre es igual al total.
- Quien crea el cobro no tiene cuota.
- Cuotas de monto fijo, sin pagos parciales.
- Cancelar el cobro anula solo las cuotas pendientes; las pagadas no se
  devuelven automáticamente.
- El cobro pasa a COMPLETED en la misma transacción del último pago.
- Pagar una cuota bloquea la fila de la cuota y verifica su estado y el del
  cobro dentro de la misma transacción (evita doble pago y pagar cobros cancelados).

## Cómo trabajar conmigo (instrucciones para Claude)
- Antes de implementar algo grande, propón un plan corto y espera mi OK.
- No cambies el esquema de BD ni estas decisiones sin preguntarme primero:
  si ves un problema, explícalo y propón alternativas.
- Todo lo que mueva plata debe tener tests, incluyendo concurrencia
  (varios hilos, verificar que la plata total no cambia y ningún saldo es negativo).
- Corre `./mvnw test` después de cada cambio y muéstrame el resultado real.
  No digas que algo funciona sin haberlo ejecutado.
- Explícame brevemente el porqué de cada decisión técnica no obvia: tengo que
  defender este código en una entrevista de pair programming.
- Código y nombres en inglés; comentarios y mensajes de error en español.

## Comandos
- Correr la app: `./mvnw spring-boot:run` (levanta Postgres con Docker Compose)
- Tests: `./mvnw test` (requiere Docker abierto)
