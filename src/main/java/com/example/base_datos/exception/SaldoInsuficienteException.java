package com.example.base_datos.exception;

import java.math.BigDecimal;

public class SaldoInsuficienteException extends NegocioException {

    public SaldoInsuficienteException(String numero, BigDecimal disponible, BigDecimal solicitado) {
        super("TX004", "Saldo insuficiente en " + numero + ": disponible " + disponible + ", solicitado " + solicitado);
    }

    SaldoInsuficienteException(String mensaje) {
        super("TX004", mensaje);
    }
}
