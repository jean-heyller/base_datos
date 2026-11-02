package com.example.base_datos.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fachada de movimientos. Tres estrategias de control de concurrencia:
 * <ol>
 *   <li>{@link #transferir} – bloqueo PESIMISTA (SELECT ... FOR NO KEY UPDATE, orden por id).</li>
 *   <li>{@link #transferirOptimista} – bloqueo OPTIMISTA (@Version).</li>
 *   <li>{@link #transferirConProcedimiento} – lógica en el procedimiento almacenado sp_transferir.</li>
 * </ol>
 * Todas se ejecutan bajo el {@link Reintentador}: ante un deadlock, timeout de lock, fallo de
 * serialización o conflicto de versión, la transacción COMPLETA se repite. Esta capa NO es
 * transaccional a propósito: cada intento abre y cierra su propia transacción en {@link MovimientosTx}.
 */
@Service
public class TransferenciaService {

    private final MovimientosTx tx;
    private final Reintentador reintentador;

    public TransferenciaService(MovimientosTx tx, Reintentador reintentador) {
        this.tx = tx;
        this.reintentador = reintentador;
    }

    public Comprobante transferir(SolicitudTransferencia s) {
        return reintentador.ejecutar("transferencia-pesimista", () -> tx.transferirPesimista(s));
    }

    public Comprobante transferirOptimista(SolicitudTransferencia s) {
        return reintentador.ejecutar("transferencia-optimista", () -> tx.transferirOptimista(s));
    }

    public Comprobante transferirConProcedimiento(SolicitudTransferencia s) {
        return reintentador.ejecutar("transferencia-procedimiento", () -> tx.transferirProcedimiento(s));
    }

    public Comprobante depositar(String numero, BigDecimal monto, UUID referencia) {
        UUID ref = referencia != null ? referencia : UUID.randomUUID();   // fija ANTES de reintentar
        return reintentador.ejecutar("deposito", () -> tx.depositar(numero, monto, ref));
    }
}
