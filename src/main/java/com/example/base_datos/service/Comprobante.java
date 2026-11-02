package com.example.base_datos.service;

import com.example.base_datos.domain.Canal;
import com.example.base_datos.domain.Cuenta;
import com.example.base_datos.domain.TipoTransaccion;
import com.example.base_datos.domain.Transaccion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Vista inmutable de una transacción confirmada (se construye dentro de la transacción). */
public record Comprobante(Long id, UUID referencia, TipoTransaccion tipo, Canal canal,
                          String cuentaOrigen, String cuentaDestino, BigDecimal monto, Instant fecha) {

    public static Comprobante de(Transaccion t) {
        return new Comprobante(t.getId(), t.getReferencia(), t.getTipo(), t.getCanal(),
                numero(t.getCuentaOrigen()), numero(t.getCuentaDestino()), t.getMonto(), t.getCreadoEn());
    }

    private static String numero(Cuenta c) {
        return c == null ? null : c.getNumero();
    }
}
