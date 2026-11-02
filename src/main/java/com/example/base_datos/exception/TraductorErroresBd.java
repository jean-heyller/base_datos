package com.example.base_datos.exception;

import org.springframework.dao.DataAccessException;

import java.sql.SQLException;

/**
 * Traduce los SQLSTATE propios lanzados con {@code RAISE EXCEPTION ... USING ERRCODE = 'TXnnn'}
 * en los procedimientos almacenados a excepciones de negocio de Java.
 */
public final class TraductorErroresBd {

    private TraductorErroresBd() {
    }

    public static RuntimeException traducir(DataAccessException ex) {
        SQLException sql = buscarSqlException(ex);
        if (sql == null || sql.getSQLState() == null) {
            return ex;
        }
        String mensaje = limpiar(sql.getMessage());
        return switch (sql.getSQLState()) {
            case "TX001" -> new MontoInvalidoException(mensaje);
            case "TX002" -> new CuentaNoEncontradaException(mensaje, true);
            case "TX003" -> new CuentaInactivaException(mensaje);
            case "TX004" -> new SaldoInsuficienteException(mensaje);
            case "TX005" -> new MismaCuentaException(mensaje, true);
            default -> ex;
        };
    }

    public static SQLException buscarSqlException(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException s) {
                return s;
            }
            t = t.getCause();
        }
        return null;
    }

    /** "ERROR: Saldo insuficiente ...\n  Where: PL/pgSQL ..." -> "Saldo insuficiente ..." */
    private static String limpiar(String mensaje) {
        if (mensaje == null) {
            return "Error de base de datos";
        }
        String linea = mensaje.lines().findFirst().orElse(mensaje);
        return linea.startsWith("ERROR: ") ? linea.substring(7) : linea;
    }
}
