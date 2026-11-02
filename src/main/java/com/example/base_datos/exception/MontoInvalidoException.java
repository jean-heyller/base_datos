package com.example.base_datos.exception;

import java.math.BigDecimal;

public class MontoInvalidoException extends NegocioException {

    public MontoInvalidoException(String mensaje) {
        super("TX001", mensaje);
    }

    public static void validar(BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            throw new MontoInvalidoException("Monto inválido: " + monto);
        }
        if (monto.stripTrailingZeros().scale() > 2) {
            throw new MontoInvalidoException("El monto admite máximo 2 decimales: " + monto);
        }
    }
}
