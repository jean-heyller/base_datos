package com.example.base_datos.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Orden de transferencia. La {@code referencia} es la clave de idempotencia: si el cliente
 * reintenta (timeout de red, doble clic) con la misma referencia, el dinero no se mueve dos veces.
 */
public record SolicitudTransferencia(String origen, String destino, BigDecimal monto,
                                     UUID referencia, String descripcion) {

    public SolicitudTransferencia {
        if (referencia == null) {
            referencia = UUID.randomUUID();
        }
    }

    public static SolicitudTransferencia de(String origen, String destino, String monto) {
        return new SolicitudTransferencia(origen, destino, new BigDecimal(monto), null, null);
    }
}
