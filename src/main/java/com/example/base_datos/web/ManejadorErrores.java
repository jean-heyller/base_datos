package com.example.base_datos.web;

import com.example.base_datos.exception.NegocioException;
import com.example.base_datos.service.Reintentador;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Traduce errores de negocio y de concurrencia a respuestas RFC 9457 (ProblemDetail). */
@RestControllerAdvice
public class ManejadorErrores extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NegocioException.class)
    public ProblemDetail negocio(NegocioException e) {
        HttpStatus status = switch (e.getCodigo()) {
            case "TX002" -> HttpStatus.NOT_FOUND;
            case "TX004", "TX003" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setTitle("Transacción rechazada (ROLLBACK)");
        pd.setProperty("codigo", e.getCodigo());
        return pd;
    }

    /** Violación de una restricción del motor (UNIQUE, CHECK, FK): la última barrera de consistencia. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integridad(DataIntegrityViolationException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "La operación viola una restricción de integridad de la base de datos");
        pd.setTitle("Restricción de integridad");
        return pd;
    }

    /** Conflicto de concurrencia que persistió tras agotar los reintentos. */
    @ExceptionHandler(TransientDataAccessException.class)
    public ProblemDetail concurrencia(TransientDataAccessException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Conflicto de concurrencia, intente de nuevo con la misma referencia");
        pd.setTitle("Conflicto de concurrencia");
        pd.setProperty("reintentable", Reintentador.esReintentable(e));
        return pd;
    }
}
