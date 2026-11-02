-- =====================================================================
-- V2: Índices (optimización)
-- Cada índice responde a un patrón de acceso concreto del sistema.
-- Los UNIQUE/PK de V1 ya crean índices B-tree implícitos sobre:
--   cliente(documento), cliente(email), cuenta(numero), transaccion(referencia)
-- =====================================================================

-- PostgreSQL NO indexa automáticamente las FKs. Sin estos índices, cada
-- DELETE/UPDATE de la tabla padre y cada JOIN haría un Seq Scan.
CREATE INDEX idx_cuenta_cliente ON cuenta (cliente_id);

-- Historial de movimientos de una cuenta, del más reciente al más antiguo.
-- Índice compuesto (cuenta, fecha DESC) + INCLUDE = "covering index":
-- la consulta del extracto se resuelve con Index Only Scan, sin tocar la tabla.
CREATE INDEX idx_tx_origen_fecha
    ON transaccion (cuenta_origen_id, creado_en DESC)
    INCLUDE (monto, tipo);

CREATE INDEX idx_tx_destino_fecha
    ON transaccion (cuenta_destino_id, creado_en DESC)
    INCLUDE (monto, tipo);

-- Índice PARCIAL: solo las cuentas no activas (minoría). Pequeño y barato
-- de mantener; acelera los reportes de cuentas bloqueadas/cerradas.
CREATE INDEX idx_cuenta_no_activa
    ON cuenta (estado)
    WHERE estado <> 'ACTIVA';

-- Auditoría por cuenta (consultas puntuales).
CREATE INDEX idx_auditoria_cuenta ON auditoria_saldo (cuenta_id, fecha DESC);

-- Índice BRIN para rangos de fecha en tablas append-only muy grandes:
-- ocupa KB en lugar de MB porque las filas llegan ordenadas por tiempo.
CREATE INDEX idx_auditoria_fecha_brin ON auditoria_saldo USING brin (fecha);
CREATE INDEX idx_tx_fecha_brin        ON transaccion     USING brin (creado_en);

-- Fallidos por fecha (monitoreo / alertas).
CREATE INDEX idx_tx_fallida_fecha ON transaccion_fallida (creado_en DESC);
