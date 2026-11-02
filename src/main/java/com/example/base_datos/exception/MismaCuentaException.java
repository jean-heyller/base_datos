package com.example.base_datos.exception;

public class MismaCuentaException extends NegocioException {

    public MismaCuentaException(String numero) {
        this(numero, false);
    }

    MismaCuentaException(String texto, boolean esMensajeCompleto) {
        super("TX005", esMensajeCompleto ? texto : "La cuenta origen y destino son la misma (" + texto + ")");
    }
}
