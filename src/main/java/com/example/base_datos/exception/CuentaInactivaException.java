package com.example.base_datos.exception;

import com.example.base_datos.domain.EstadoCuenta;

public class CuentaInactivaException extends NegocioException {

    public CuentaInactivaException(String numero, EstadoCuenta estado) {
        super("TX003", "Cuenta " + numero + " no está activa (" + estado + ")");
    }

    CuentaInactivaException(String mensaje) {
        super("TX003", mensaje);
    }
}
