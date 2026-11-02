-- =====================================================================
-- V3: Procedimientos almacenados y funciones (TCL en el RDBMS)
--
-- Códigos SQLSTATE propios (la capa Java los traduce a excepciones):
--   TX001 monto inválido         TX002 cuenta no existe
--   TX003 cuenta no activa       TX004 saldo insuficiente
--   TX005 misma cuenta
--
-- Ventajas frente a hacerlo desde la aplicación:
--   * 1 viaje de red en lugar de ~6 (select, lock, update x2, insert, commit)
--   * los bloqueos se mantienen el menor tiempo posible
--   * plan de ejecución cacheado por PL/pgSQL
--   * la regla de negocio queda junto a los datos (cualquier cliente la respeta)
-- =====================================================================

-- ---------------------------------------------------------------------
-- sp_transferir: transferencia atómica entre dos cuentas.
-- Atomicidad: un procedimiento se ejecuta dentro de la transacción del
-- llamador; cualquier RAISE EXCEPTION revierte TODO lo que hizo.
-- Aislamiento: SELECT ... FOR NO KEY UPDATE en orden de id (evita deadlocks).
-- Idempotencia: si la referencia ya existe, devuelve la transacción previa.
-- ---------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_transferir(
    p_origen      VARCHAR,
    p_destino     VARCHAR,
    p_monto       NUMERIC,
    p_referencia  UUID,
    p_descripcion VARCHAR DEFAULT NULL,
    p_canal       VARCHAR DEFAULT 'PROCEDURE',
    INOUT p_tx_id BIGINT  DEFAULT NULL
)
LANGUAGE plpgsql AS $$
DECLARE
    v_origen   cuenta%ROWTYPE;
    v_destino  cuenta%ROWTYPE;
BEGIN
    IF p_monto IS NULL OR p_monto <= 0 OR scale(p_monto) > 2 THEN
        RAISE EXCEPTION 'Monto inválido: %', p_monto USING ERRCODE = 'TX001';
    END IF;
    IF p_origen = p_destino THEN
        RAISE EXCEPTION 'La cuenta origen y destino son la misma (%)', p_origen USING ERRCODE = 'TX005';
    END IF;

    -- Idempotencia: reintentos del cliente con la misma referencia no duplican dinero
    SELECT id INTO p_tx_id FROM transaccion WHERE referencia = p_referencia;
    IF FOUND THEN
        RETURN;
    END IF;

    -- Bloqueo pesimista de AMBAS filas en orden determinista (id ascendente).
    -- FOR NO KEY UPDATE no choca con los KEY SHARE que toman las FKs de transaccion.
    -- Dos transferencias cruzadas A->B y B->A piden los locks en el mismo
    -- orden, por lo que nunca forman un ciclo de espera (deadlock).
    PERFORM 1 FROM cuenta WHERE numero IN (p_origen, p_destino) ORDER BY id FOR NO KEY UPDATE;

    SELECT * INTO v_origen  FROM cuenta WHERE numero = p_origen;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Cuenta origen % no existe', p_origen USING ERRCODE = 'TX002';
    END IF;
    SELECT * INTO v_destino FROM cuenta WHERE numero = p_destino;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Cuenta destino % no existe', p_destino USING ERRCODE = 'TX002';
    END IF;

    IF v_origen.estado <> 'ACTIVA' THEN
        RAISE EXCEPTION 'Cuenta origen % no está activa (%)', p_origen, v_origen.estado USING ERRCODE = 'TX003';
    END IF;
    IF v_destino.estado <> 'ACTIVA' THEN
        RAISE EXCEPTION 'Cuenta destino % no está activa (%)', p_destino, v_destino.estado USING ERRCODE = 'TX003';
    END IF;
    IF v_origen.saldo < p_monto THEN
        RAISE EXCEPTION 'Saldo insuficiente en %: disponible %, solicitado %',
              p_origen, v_origen.saldo, p_monto USING ERRCODE = 'TX004';
    END IF;

    -- version + 1 mantiene coherente el bloqueo optimista de JPA
    UPDATE cuenta SET saldo = saldo - p_monto, version = version + 1 WHERE id = v_origen.id;
    UPDATE cuenta SET saldo = saldo + p_monto, version = version + 1 WHERE id = v_destino.id;

    INSERT INTO transaccion (referencia, tipo, canal, cuenta_origen_id, cuenta_destino_id, monto, descripcion)
    VALUES (p_referencia, 'TRANSFERENCIA', p_canal, v_origen.id, v_destino.id, p_monto, p_descripcion)
    RETURNING id INTO p_tx_id;
END;
$$;

-- ---------------------------------------------------------------------
-- sp_depositar
-- ---------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_depositar(
    p_numero      VARCHAR,
    p_monto       NUMERIC,
    p_referencia  UUID,
    INOUT p_tx_id BIGINT DEFAULT NULL
)
LANGUAGE plpgsql AS $$
DECLARE
    v_cuenta cuenta%ROWTYPE;
BEGIN
    IF p_monto IS NULL OR p_monto <= 0 OR scale(p_monto) > 2 THEN
        RAISE EXCEPTION 'Monto inválido: %', p_monto USING ERRCODE = 'TX001';
    END IF;

    SELECT id INTO p_tx_id FROM transaccion WHERE referencia = p_referencia;
    IF FOUND THEN
        RETURN;
    END IF;

    SELECT * INTO v_cuenta FROM cuenta WHERE numero = p_numero FOR NO KEY UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Cuenta % no existe', p_numero USING ERRCODE = 'TX002';
    END IF;
    IF v_cuenta.estado <> 'ACTIVA' THEN
        RAISE EXCEPTION 'Cuenta % no está activa (%)', p_numero, v_cuenta.estado USING ERRCODE = 'TX003';
    END IF;

    UPDATE cuenta SET saldo = saldo + p_monto, version = version + 1 WHERE id = v_cuenta.id;

    INSERT INTO transaccion (referencia, tipo, canal, cuenta_destino_id, monto)
    VALUES (p_referencia, 'DEPOSITO', 'PROCEDURE', v_cuenta.id, p_monto)
    RETURNING id INTO p_tx_id;
END;
$$;

-- ---------------------------------------------------------------------
-- sp_aplicar_intereses: proceso masivo con CONTROL DE TRANSACCIONES
-- DENTRO del procedimiento (COMMIT por lotes, PostgreSQL >= 11).
--   * Cada lote se confirma de forma independiente: si el proceso cae a
--     mitad, los lotes ya confirmados son DURABLES y no se repiten
--     (la marca "ultimo_id" permite reanudar).
--   * Los locks se liberan en cada COMMIT: el proceso nocturno no bloquea
--     a los usuarios durante minutos.
-- Debe invocarse con CALL fuera de un bloque BEGIN (autocommit).
-- ---------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_aplicar_intereses(
    p_tasa              NUMERIC,
    p_tamano_lote       INT     DEFAULT 1000,
    INOUT p_procesadas  INT     DEFAULT 0,
    INOUT p_lotes       INT     DEFAULT 0
)
LANGUAGE plpgsql AS $$
DECLARE
    v_ultimo_id BIGINT := 0;
    v_max_lote  BIGINT;
    v_filas     INT;
BEGIN
    IF p_tasa IS NULL OR p_tasa <= 0 OR p_tasa > 0.10 THEN
        RAISE EXCEPTION 'Tasa inválida: %', p_tasa USING ERRCODE = 'TX001';
    END IF;
    p_procesadas := 0;
    p_lotes      := 0;

    LOOP
        SELECT max(id) INTO v_max_lote
        FROM (SELECT id FROM cuenta
              WHERE id > v_ultimo_id AND estado = 'ACTIVA' AND saldo > 0
              ORDER BY id
              LIMIT p_tamano_lote) lote;

        EXIT WHEN v_max_lote IS NULL;

        UPDATE cuenta
           SET saldo = saldo + round(saldo * p_tasa, 2),
               version = version + 1
         WHERE id > v_ultimo_id AND id <= v_max_lote
           AND estado = 'ACTIVA' AND saldo > 0
           AND round(saldo * p_tasa, 2) > 0;
        GET DIAGNOSTICS v_filas = ROW_COUNT;

        p_procesadas := p_procesadas + v_filas;
        p_lotes      := p_lotes + 1;
        v_ultimo_id  := v_max_lote;

        COMMIT;   -- TCL dentro del procedimiento: el lote queda durable
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------------
-- Funciones de consulta (STABLE: el planificador puede optimizarlas)
-- ---------------------------------------------------------------------

-- Invariante de consistencia: el dinero total del sistema.
CREATE OR REPLACE FUNCTION fn_saldo_total() RETURNS NUMERIC
LANGUAGE sql STABLE AS $$
    SELECT coalesce(sum(saldo), 0) FROM cuenta;
$$;

-- Extracto de una cuenta (usa los covering index idx_tx_*_fecha).
CREATE OR REPLACE FUNCTION fn_extracto(p_numero VARCHAR, p_limite INT DEFAULT 50)
RETURNS TABLE (
    transaccion_id BIGINT,
    tipo           VARCHAR,
    movimiento     NUMERIC,
    contraparte    VARCHAR,
    fecha          TIMESTAMPTZ
)
LANGUAGE sql STABLE AS $$
    WITH c AS (SELECT id FROM cuenta WHERE numero = p_numero)
    SELECT * FROM (
        SELECT t.id, t.tipo, -t.monto, d.numero, t.creado_en
          FROM transaccion t
          JOIN c ON t.cuenta_origen_id = c.id
          LEFT JOIN cuenta d ON d.id = t.cuenta_destino_id
        UNION ALL
        SELECT t.id, t.tipo, t.monto, o.numero, t.creado_en
          FROM transaccion t
          JOIN c ON t.cuenta_destino_id = c.id
          LEFT JOIN cuenta o ON o.id = t.cuenta_origen_id
    ) movs
    ORDER BY 5 DESC, 1 DESC
    LIMIT p_limite;
$$;
