-- =====================================================================
-- Guion de demostración manual de TCL y ACID en PostgreSQL (psql/DBeaver).
-- Ejecutar sobre la BD "banco" ya migrada por la aplicación (Flyway).
-- Las demos de aislamiento requieren DOS sesiones: SESIÓN A y SESIÓN B.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. TCL básico: BEGIN / SAVEPOINT / ROLLBACK TO / RELEASE / COMMIT
-- ---------------------------------------------------------------------
SELECT numero, saldo FROM cuenta WHERE numero IN ('ACC-001', 'ACC-002');

BEGIN;
    UPDATE cuenta SET saldo = saldo - 100 WHERE numero = 'ACC-001';
    UPDATE cuenta SET saldo = saldo + 100 WHERE numero = 'ACC-002';
    SAVEPOINT despues_primera;

    UPDATE cuenta SET saldo = saldo - 50 WHERE numero = 'ACC-001';
    -- me arrepiento solo de esta parte:
    ROLLBACK TO SAVEPOINT despues_primera;

    RELEASE SAVEPOINT despues_primera;
COMMIT;                                    -- queda confirmada solo la transferencia de 100

-- ---------------------------------------------------------------------
-- 2. ATOMICIDAD: un error aborta toda la transacción
-- ---------------------------------------------------------------------
BEGIN;
    UPDATE cuenta SET saldo = saldo + 1000000 WHERE numero = 'ACC-002';
    UPDATE cuenta SET saldo = saldo - 1000000000 WHERE numero = 'ACC-001';  -- viola CHECK saldo >= 0
    -- ERROR: ... ck_cuenta_saldo_no_negativo. La transacción queda "aborted":
    SELECT 1;                              -- ERROR: current transaction is aborted
ROLLBACK;                                  -- el +1.000.000 a ACC-002 también se deshizo

-- ---------------------------------------------------------------------
-- 3. Procedimiento almacenado dentro de la transacción del llamador
-- ---------------------------------------------------------------------
BEGIN;
    CALL sp_transferir('ACC-001', 'ACC-003', 25.00, gen_random_uuid(), 'demo');
    SELECT numero, saldo FROM cuenta WHERE numero IN ('ACC-001', 'ACC-003');
ROLLBACK;                                  -- el llamador decide: nada de lo anterior persiste

CALL sp_transferir('ACC-001', 'ACC-003', 25.00, gen_random_uuid());           -- autocommit: confirmada
CALL sp_transferir('ACC-003', 'ACC-001', 99999999, gen_random_uuid());        -- ERROR TX004 saldo insuficiente
CALL sp_transferir('ACC-001', 'ACC-004', 10, gen_random_uuid());              -- ERROR TX003 cuenta bloqueada

-- Procedimiento con COMMIT interno por lotes (solo fuera de BEGIN):
CALL sp_aplicar_intereses(0.005, 2, NULL, NULL);
SELECT txid, count(*) FROM auditoria_saldo GROUP BY txid ORDER BY txid DESC LIMIT 5;  -- 1 txid por lote

-- ---------------------------------------------------------------------
-- 4. AISLAMIENTO (dos sesiones)
-- ---------------------------------------------------------------------
-- 4.a Sin lecturas sucias
-- SESIÓN A:  BEGIN; UPDATE cuenta SET saldo = 0 WHERE numero = 'ACC-002';
-- SESIÓN B:  SELECT saldo FROM cuenta WHERE numero = 'ACC-002';   -- ve el valor anterior
-- SESIÓN A:  ROLLBACK;

-- 4.b Lectura no repetible en READ COMMITTED vs. foto estable en REPEATABLE READ
-- SESIÓN A:  BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
--            SELECT saldo FROM cuenta WHERE numero = 'ACC-002';       -- X
-- SESIÓN B:  UPDATE cuenta SET saldo = saldo + 1 WHERE numero = 'ACC-002';   -- autocommit
-- SESIÓN A:  SELECT saldo FROM cuenta WHERE numero = 'ACC-002';       -- sigue X (con READ COMMITTED sería X+1)
--            COMMIT;

-- 4.c Bloqueo pesimista y actualización perdida evitada
-- SESIÓN A:  BEGIN; SELECT saldo FROM cuenta WHERE numero = 'ACC-001' FOR NO KEY UPDATE;
-- SESIÓN B:  UPDATE cuenta SET saldo = saldo - 1 WHERE numero = 'ACC-001';   -- queda ESPERANDO
-- SESIÓN A:  UPDATE cuenta SET saldo = saldo - 1 WHERE numero = 'ACC-001'; COMMIT;   -- B continúa
--            Resultado: -2 (ninguna actualización se perdió)

-- 4.d SERIALIZABLE: anomalía de escritura sesgada (write skew) detectada
-- SESIÓN A:  BEGIN ISOLATION LEVEL SERIALIZABLE;
--            SELECT sum(saldo) FROM cuenta WHERE cliente_id = 3;
-- SESIÓN B:  BEGIN ISOLATION LEVEL SERIALIZABLE;
--            SELECT sum(saldo) FROM cuenta WHERE cliente_id = 3;
-- SESIÓN A:  UPDATE cuenta SET saldo = saldo - 100 WHERE numero = 'ACC-003'; COMMIT;
-- SESIÓN B:  UPDATE cuenta SET saldo = saldo - 100 WHERE numero = 'ACC-004';
--            COMMIT;   -- ERROR 40001 could not serialize access -> la app debe reintentar

-- 4.e Deadlock (orden de bloqueo opuesto) -> PostgreSQL lo detecta y aborta a una (40P01)
-- SESIÓN A:  BEGIN; UPDATE cuenta SET saldo = saldo + 1 WHERE numero = 'ACC-001';
-- SESIÓN B:  BEGIN; UPDATE cuenta SET saldo = saldo + 1 WHERE numero = 'ACC-002';
-- SESIÓN A:  UPDATE cuenta SET saldo = saldo + 1 WHERE numero = 'ACC-002';   -- espera
-- SESIÓN B:  UPDATE cuenta SET saldo = saldo + 1 WHERE numero = 'ACC-001';   -- ERROR: deadlock detected
-- (sp_transferir lo evita bloqueando siempre en orden de id)

-- ---------------------------------------------------------------------
-- 5. DURABILIDAD
-- ---------------------------------------------------------------------
SHOW fsync;               -- on: el WAL se fuerza a disco
SHOW synchronous_commit;  -- on: COMMIT no responde hasta que el WAL está en disco
SHOW wal_level;
-- Prueba: confirmar una transferencia, `docker restart banco-postgres`, volver a consultar.

-- ---------------------------------------------------------------------
-- 6. ÍNDICES: planes de ejecución
-- ---------------------------------------------------------------------
EXPLAIN ANALYZE
SELECT monto, tipo, creado_en FROM transaccion
 WHERE cuenta_origen_id = 1 ORDER BY creado_en DESC LIMIT 20;      -- Index (Only) Scan idx_tx_origen_fecha

EXPLAIN ANALYZE SELECT * FROM fn_extracto('ACC-001', 20);
EXPLAIN SELECT id FROM transaccion WHERE referencia = '11111111-1111-1111-1111-111111111111';

-- Tamaño de cada índice (BRIN vs B-tree)
SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) AS tamano, idx_scan
  FROM pg_stat_user_indexes ORDER BY pg_relation_size(indexrelid) DESC;

-- Invariante de consistencia: el total solo cambia por depósitos/intereses, nunca por transferencias
SELECT fn_saldo_total();
