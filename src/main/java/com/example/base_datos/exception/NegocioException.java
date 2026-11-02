package com.example.base_datos.exception;

/**
 * Error de negocio. Es RuntimeException, así que {@code @Transactional} hace ROLLBACK
 * automáticamente (además declaramos rollbackFor = Exception.class para cubrir checked).
 */
public abstract class NegocioException extends RuntimeException {

    private final String codigo;

    protected NegocioException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    /** Código estable (coincide con el SQLSTATE que lanzan los procedimientos). */
    public String getCodigo() {
        return codigo;
    }
}
