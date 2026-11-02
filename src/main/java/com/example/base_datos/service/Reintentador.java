package com.example.base_datos.service;

import com.example.base_datos.config.ReintentosProperties;
import com.example.base_datos.exception.TraductorErroresBd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Reintenta una TRANSACCIÓN COMPLETA cuando falla por un conflicto de concurrencia transitorio.
 * <p>
 * Importante: el reintento debe envolver la transacción entera (BEGIN..COMMIT), nunca ejecutarse
 * dentro de ella: tras un error de serialización/deadlock PostgreSQL deja la transacción abortada
 * y solo se puede hacer ROLLBACK.
 * <ul>
 *   <li>40001 serialization_failure (SERIALIZABLE / REPEATABLE READ)</li>
 *   <li>40P01 deadlock_detected</li>
 *   <li>55P03 lock_not_available (lock_timeout)</li>
 *   <li>OptimisticLockingFailureException (@Version)</li>
 * </ul>
 */
@Component
public class Reintentador {

    private static final Logger log = LoggerFactory.getLogger(Reintentador.class);
    private static final Set<String> SQLSTATE_REINTENTABLES = Set.of("40001", "40P01", "55P03");

    private final ReintentosProperties props;

    public Reintentador(ReintentosProperties props) {
        this.props = props;
    }

    public <T> T ejecutar(String operacion, Supplier<T> transaccion) {
        for (int intento = 1; ; intento++) {
            try {
                return transaccion.get();
            } catch (RuntimeException e) {
                if (!esReintentable(e) || intento >= props.maximos()) {
                    throw e;
                }
                long exponencial = Math.min(props.esperaMaximaMs(),
                        props.esperaInicialMs() << Math.min(intento - 1, 20));             // backoff exponencial con tope
                long espera = ThreadLocalRandom.current().nextLong(exponencial / 2, exponencial + 1); // jitter
                log.debug("{}: conflicto de concurrencia (intento {}/{}), reintento en {} ms: {}",
                        operacion, intento, props.maximos(), espera, e.getClass().getSimpleName());
                dormir(espera);
            }
        }
    }

    public static boolean esReintentable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConcurrencyFailureException) {
                return true;
            }
        }
        SQLException sql = TraductorErroresBd.buscarSqlException(e);
        return sql != null && SQLSTATE_REINTENTABLES.contains(sql.getSQLState());
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reintento interrumpido", ie);
        }
    }
}
