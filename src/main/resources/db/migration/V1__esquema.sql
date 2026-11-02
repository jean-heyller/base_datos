-- =====================================================================
-- V1: Esquema base del sistema bancario
-- CONSISTENCIA (la "C" de ACID): las reglas de negocio críticas se
-- declaran en el motor (PK, FK, UNIQUE, CHECK, NOT NULL) para que
-- NINGUNA transacción, venga de donde venga, pueda dejar la BD en un
-- estado inválido. Si una regla se viola, el motor aborta la sentencia.
-- =====================================================================

CREATE TABLE cliente (
    id          BIGSERIAL    PRIMARY KEY,
    documento   VARCHAR(20)  NOT NULL UNIQUE,
    nombre      VARCHAR(120) NOT NULL,
    email       VARCHAR(120) NOT NULL UNIQUE,
    creado_en   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE cuenta (
    id              BIGSERIAL     PRIMARY KEY,
    numero          VARCHAR(20)   NOT NULL UNIQUE,
    cliente_id      BIGINT        NOT NULL REFERENCES cliente (id) ON DELETE RESTRICT,
    saldo           NUMERIC(15,2) NOT NULL DEFAULT 0,
    estado          VARCHAR(10)   NOT NULL DEFAULT 'ACTIVA',
    version         BIGINT        NOT NULL DEFAULT 0,   -- bloqueo optimista (JPA @Version)
    creado_en       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actualizado_en  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_cuenta_saldo_no_negativo CHECK (saldo >= 0),
    CONSTRAINT ck_cuenta_estado CHECK (estado IN ('ACTIVA', 'BLOQUEADA', 'CERRADA'))
);

-- Libro de movimientos (append-only). Solo existen transacciones confirmadas:
-- si la transacción de negocio hace ROLLBACK, la fila nunca llega a existir.
CREATE TABLE transaccion (
    id                 BIGSERIAL     PRIMARY KEY,
    referencia         UUID          NOT NULL,          -- clave de idempotencia
    tipo               VARCHAR(15)   NOT NULL,
    canal              VARCHAR(10)   NOT NULL,
    cuenta_origen_id   BIGINT        REFERENCES cuenta (id),
    cuenta_destino_id  BIGINT        REFERENCES cuenta (id),
    monto              NUMERIC(15,2) NOT NULL,
    descripcion        VARCHAR(200),
    creado_en          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_transaccion_referencia UNIQUE (referencia),
    CONSTRAINT ck_tx_monto_positivo CHECK (monto > 0),
    CONSTRAINT ck_tx_tipo  CHECK (tipo IN ('DEPOSITO', 'RETIRO', 'TRANSFERENCIA')),
    CONSTRAINT ck_tx_canal CHECK (canal IN ('JPA', 'PROCEDURE', 'LOTE')),
    CONSTRAINT ck_tx_cuentas_distintas CHECK (cuenta_origen_id IS DISTINCT FROM cuenta_destino_id),
    CONSTRAINT ck_tx_forma CHECK (
        (tipo = 'DEPOSITO'      AND cuenta_origen_id IS NULL     AND cuenta_destino_id IS NOT NULL) OR
        (tipo = 'RETIRO'        AND cuenta_origen_id IS NOT NULL AND cuenta_destino_id IS NULL)     OR
        (tipo = 'TRANSFERENCIA' AND cuenta_origen_id IS NOT NULL AND cuenta_destino_id IS NOT NULL)
    )
);

-- Registro de intentos fallidos. Se escribe en una transacción INDEPENDIENTE
-- (REQUIRES_NEW en Spring / bloque autónomo) para que sobreviva al ROLLBACK
-- de la transacción de negocio. Sin FKs a propósito: no debe competir por
-- bloqueos con la transacción principal que aún los mantiene.
CREATE TABLE transaccion_fallida (
    id               BIGSERIAL     PRIMARY KEY,
    referencia       UUID,
    tipo             VARCHAR(15)   NOT NULL,
    cuenta_origen    VARCHAR(20),
    cuenta_destino   VARCHAR(20),
    monto            NUMERIC(15,2),
    motivo           VARCHAR(400)  NOT NULL,
    creado_en        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Auditoría de cambios de saldo, alimentada por trigger (misma transacción:
-- si la operación hace ROLLBACK, su auditoría también desaparece -> atomicidad).
CREATE TABLE auditoria_saldo (
    id              BIGSERIAL     PRIMARY KEY,
    cuenta_id       BIGINT        NOT NULL,
    saldo_anterior  NUMERIC(15,2) NOT NULL,
    saldo_nuevo     NUMERIC(15,2) NOT NULL,
    usuario_bd      VARCHAR(64)   NOT NULL DEFAULT current_user,
    txid            BIGINT        NOT NULL DEFAULT txid_current(),
    fecha           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Trigger: auditoría + marca de actualización
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_cuenta_auditar_saldo() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.actualizado_en := now();
    IF NEW.saldo IS DISTINCT FROM OLD.saldo THEN
        INSERT INTO auditoria_saldo (cuenta_id, saldo_anterior, saldo_nuevo)
        VALUES (OLD.id, OLD.saldo, NEW.saldo);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cuenta_auditar_saldo
    BEFORE UPDATE ON cuenta
    FOR EACH ROW
    EXECUTE FUNCTION trg_cuenta_auditar_saldo();
