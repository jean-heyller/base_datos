# Sistema de Transacciones Robusto y Eficiente (TCL + ACID)

Sistema bancario de transferencias con **PostgreSQL** + **Spring Boot 4 / JPA (Hibernate 7)**.
Implementa el control de transacciones (TCL) tanto en el **RDBMS** (procedimientos PL/pgSQL, `SAVEPOINT`,
`COMMIT` dentro de procedimientos) como en el **framework** (`@Transactional`, `TransactionTemplate`,
propagación, aislamiento, timeouts, reintentos). También incluye optimizaciones con procedimientos almacenados e índices.

## Ejecutar

| Opción | Comando |
|---|---|
| Sin instalar nada (PostgreSQL embebido) | `gradlew bootRun --args="--spring.profiles.active=embedded"` |
| Con PostgreSQL en Docker | `docker compose up -d` y luego `gradlew bootRun` |
| Tests (34, contra PostgreSQL real embebido) | `gradlew test` |

Flyway crea el esquema, los índices, los procedimientos y los datos demo (`ACC-001` … `ACC-004`).

## Estructura

```
src/main/resources/db/migration/
  V1__esquema.sql          tablas, PK/FK/UNIQUE/CHECK, trigger de auditoría
  V2__indices.sql          B-tree compuestos, covering (INCLUDE), parcial, BRIN
  V3__procedimientos.sql   sp_transferir, sp_depositar, sp_aplicar_intereses (COMMIT por lotes), funciones
  V4__datos_demo.sql
src/main/java/com/example/base_datos/
  config/      TransaccionConfig (JpaTransactionManager), EmbeddedPostgresConfig, ReintentosProperties
  domain/      Cliente, Cuenta (@Version), Transaccion, TransaccionFallida
  repository/  Spring Data JPA (bloqueo FOR NO KEY UPDATE ordenado por id)
  service/     MovimientosTx (unidades transaccionales), TransferenciaService (fachada + reintentos),
               LoteService (savepoints), CuentaService (SERIALIZABLE), ReporteService (REPEATABLE READ),
               RegistroFallosService (REQUIRES_NEW), ProcedimientosDao (CALL a procedimientos), Reintentador
  web/         BancoController (REST), ManejadorErrores (ProblemDetail)
sql/demo_tcl_acid.sql      guion manual para demostrar TCL y ACID en psql con dos sesiones
```

## Cómo se garantiza cada propiedad ACID

| Propiedad | En el RDBMS | En el framework | Test que lo demuestra |
|---|---|---|---|
| **Atomicidad** | Un procedimiento corre dentro de la transacción del llamador: `RAISE EXCEPTION` revierte todo. La auditoría por trigger va en la misma transacción. | `@Transactional(rollbackFor = Exception.class)`: cualquier excepción provoca `ROLLBACK`. Lote `TODO_O_NADA`. | `AtomicidadTest` |
| **Consistencia** | `CHECK (saldo >= 0)`, `CHECK (monto > 0)`, forma de cada tipo de movimiento, FKs, `UNIQUE(referencia)`. | Validación en el dominio (`Cuenta.debitar`) y en la API (`@Valid`). | `ConsistenciaTest`: 300 transferencias concurrentes con el dinero total conservado |
| **Aislamiento** | `SELECT … FOR NO KEY UPDATE` en orden de `id` (sin deadlocks), MVCC, `lock_timeout`. | READ COMMITTED + bloqueo pesimista, bloqueo optimista `@Version`, REPEATABLE READ (reportes), SERIALIZABLE (cierre de cuenta), reintentos con backoff. | `AislamientoTest`: sin actualizaciones perdidas, sin deadlocks, sin lecturas sucias, snapshot |
| **Durabilidad** | WAL con `fsync=on`, `synchronous_commit=on`, `full_page_writes=on`. `sp_aplicar_intereses` confirma por lotes. | `REQUIRES_NEW` en el registro de fallos: queda guardado aunque la operación principal haga rollback. | `DurabilidadTest` |

## TCL usado

- **SQL / PL/pgSQL:** `BEGIN`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `ROLLBACK TO SAVEPOINT`, `RELEASE SAVEPOINT`,
  `SET TRANSACTION ISOLATION LEVEL`, `COMMIT` dentro de un `PROCEDURE` (`TclSqlTest`, `sql/demo_tcl_acid.sql`).
- **Spring:**
  - Declarativo: `@Transactional` con `propagation`, `isolation`, `timeout`, `readOnly` y `rollbackFor`.
  - Programático: `TransactionTemplate`.
  - Propagación `REQUIRED`, `REQUIRES_NEW` y `NOT_SUPPORTED`.

## Optimizaciones

1. **Procedimientos almacenados:** `sp_transferir` hace en 1 viaje de red lo que desde JPA son ~6 sentencias, y mantiene los locks el mínimo tiempo.
2. **Índices** (`IndicesTest` verifica los planes con `EXPLAIN`):
   - Índices sobre las FKs, que PostgreSQL no crea por sí solo.
   - Índices *covering* `(cuenta, fecha DESC) INCLUDE (monto, tipo)` para el extracto.
   - Índice parcial `WHERE estado <> 'ACTIVA'`.
   - BRIN sobre fechas en tablas append-only.
   - `UNIQUE(referencia)` para la idempotencia.
3. **Lote PARCIAL con savepoints:** N operaciones con un solo `COMMIT` (un solo fsync) en lugar de N.
4. **`COMMIT` por lotes dentro del procedimiento:** los procesos masivos no retienen locks durante minutos.
5. **Pool Hikari** con `auto-commit=false`, `open-in-view=false`, JDBC batching y `readOnly` en las consultas.
6. **Idempotencia:** los reintentos del cliente con la misma `referencia` no duplican el dinero.

## Problemas reales encontrados por los tests (y su solución)

- **Deadlock entre estrategias.** Hibernate ejecuta el `INSERT` del movimiento (la FK toma `KEY SHARE` sobre la cuenta) y un `FOR UPDATE` concurrente choca con él.
  Solución: `FOR NO KEY UPDATE`, que basta porque nunca se modifica la clave, y reintentar todas las estrategias, no solo la optimista.
- **Backoff exponencial sin tope.** 5 ms·2³⁹ equivale a esperar indefinidamente. Solución: `espera-maxima-ms`.
- **`connection-init-sql` con `auto-commit=false`.** Dejaba las conexiones *idle in transaction* y el `lock_timeout` se perdía.
  Solución: pasarlo como parámetro de arranque en la URL (`options=-c lock_timeout=5s`).
- **Savepoints con JPA.** `TransactionStatus.createSavepoint()` no está soportado por `HibernateJpaDialect`.
  Solución: `SAVEPOINT` en SQL sobre la misma conexión.

## API

```bash
# estrategia: PESIMISTA | OPTIMISTA | PROCEDIMIENTO
curl -X POST localhost:8080/api/transferencias -H "Content-Type: application/json" \
     -d '{"origen":"ACC-001","destino":"ACC-002","monto":150.00,"estrategia":"PROCEDIMIENTO"}'
curl -X POST localhost:8080/api/depositos -H "Content-Type: application/json" -d '{"numero":"ACC-003","monto":50}'
curl -X POST localhost:8080/api/lotes -H "Content-Type: application/json" \
     -d '{"modo":"PARCIAL","transferencias":[{"origen":"ACC-001","destino":"ACC-002","monto":10}]}'
curl -X POST localhost:8080/api/cuentas -H "Content-Type: application/json" \
     -d '{"documento":"2001","nombre":"Diana","email":"diana@demo.co","numero":"ACC-010"}'
curl -X POST localhost:8080/api/cuentas/ACC-010/cierre -H "Content-Type: application/json" -d '{"cuentaDestino":"ACC-001"}'
curl localhost:8080/api/cuentas/ACC-001
curl localhost:8080/api/cuentas/ACC-001/extracto?limite=20
curl localhost:8080/api/reportes/resumen
curl localhost:8080/api/transacciones/fallidas
curl -X POST "localhost:8080/api/procesos/intereses?tasa=0.01&lote=1000"
```

Errores: `422` (saldo insuficiente, cuenta inactiva), `404` (cuenta no existe), `409` (restricción del motor o conflicto de concurrencia persistente), `400` (validación).
