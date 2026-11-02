package com.example.base_datos.exception;

public class CuentaNoEncontradaException extends NegocioException {

    public CuentaNoEncontradaException(String numero) {
        this(numero, false);
    }

    /** esMensajeCompleto = true cuando el texto ya viene redactado desde el procedimiento almacenado. */
    CuentaNoEncontradaException(String texto, boolean esMensajeCompleto) {
        super("TX002", esMensajeCompleto ? texto : "Cuenta " + texto + " no existe");
    }
}
