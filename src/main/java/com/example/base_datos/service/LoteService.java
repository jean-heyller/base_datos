package com.example.base_datos.service;

import com.example.base_datos.domain.Canal;
import com.example.base_datos.exception.NegocioException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Procesamiento por lotes con dos políticas transaccionales:
 * <ul>
 *   <li><b>TODO_O_NADA</b>: una sola transacción. Si UNA orden falla => ROLLBACK de todo el lote (atomicidad).</li>
 *   <li><b>PARCIAL</b>: una sola transacción con un SAVEPOINT por orden. Si una orden falla =>
 *       ROLLBACK TO SAVEPOINT (solo se deshace esa orden) y se continúa; al final un único COMMIT.
 *       Es mucho más eficiente que abrir N transacciones (1 COMMIT = 1 fsync del WAL).</li>
 * </ul>
 * Los SAVEPOINT se emiten como SQL sobre la MISMA conexión de la transacción (JdbcTemplate la comparte).
 * Nota: con Hibernate, {@code TransactionStatus.createSavepoint()} lanza NestedTransactionNotSupportedException
 * porque HibernateJpaDialect no expone savepoints; como este lote solo usa JDBC, el SQL directo es seguro.
 */
@Service
public class LoteService {

    public enum Modo { TODO_O_NADA, PARCIAL }

    public enum EstadoItem { CONFIRMADA, RECHAZADA, REVERTIDA, NO_PROCESADA }

    public record ResultadoItem(int indice, UUID referencia, EstadoItem estado, Long transaccionId, String error) {
    }

    public record ResultadoLote(Modo modo, boolean confirmado, int confirmadas, int rechazadas, List<ResultadoItem> items) {
    }

    private final ProcedimientosDao procedimientos;
    private final RegistroFallosService fallos;
    private final Reintentador reintentador;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public LoteService(ProcedimientosDao procedimientos, RegistroFallosService fallos, Reintentador reintentador,
                       JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.procedimientos = procedimientos;
        this.fallos = fallos;
        this.reintentador = reintentador;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(60);
    }

    public ResultadoLote procesar(List<SolicitudTransferencia> ordenes, Modo modo) {
        // Ante deadlock/timeout de lock se reintenta el lote completo (cada intento parte de cero).
        return reintentador.ejecutar("lote-" + modo,
                () -> modo == Modo.TODO_O_NADA ? todoONada(ordenes) : parcial(ordenes));
    }

    // BEGIN; CALL ...; CALL ...; CALL ... (falla) -> ROLLBACK
    private ResultadoLote todoONada(List<SolicitudTransferencia> ordenes) {
        List<Long> ids = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> {
                for (SolicitudTransferencia s : ordenes) {
                    ids.add(procedimientos.transferir(s, Canal.LOTE));
                }
            });
        } catch (NegocioException e) {
            int fallida = ids.size();
            fallos.registrar(ordenes.get(fallida), e);
            List<ResultadoItem> items = new ArrayList<>();
            for (int i = 0; i < ordenes.size(); i++) {
                UUID ref = ordenes.get(i).referencia();
                if (i < fallida) {
                    items.add(new ResultadoItem(i, ref, EstadoItem.REVERTIDA, null, "Revertida por fallo de la orden " + fallida));
                } else if (i == fallida) {
                    items.add(new ResultadoItem(i, ref, EstadoItem.RECHAZADA, null, e.getMessage()));
                } else {
                    items.add(new ResultadoItem(i, ref, EstadoItem.NO_PROCESADA, null, null));
                }
            }
            return new ResultadoLote(Modo.TODO_O_NADA, false, 0, 1, items);
        }
        List<ResultadoItem> items = new ArrayList<>();
        for (int i = 0; i < ordenes.size(); i++) {
            items.add(new ResultadoItem(i, ordenes.get(i).referencia(), EstadoItem.CONFIRMADA, ids.get(i), null));
        }
        return new ResultadoLote(Modo.TODO_O_NADA, true, ids.size(), 0, items);
    }

    // BEGIN; SAVEPOINT orden_0; CALL ...; RELEASE SAVEPOINT orden_0;
    //        SAVEPOINT orden_1; CALL ... (falla); ROLLBACK TO SAVEPOINT orden_1; ... COMMIT
    private ResultadoLote parcial(List<SolicitudTransferencia> ordenes) {
        List<ResultadoItem> items = new ArrayList<>();
        List<RuntimeException> errores = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < ordenes.size(); i++) {
                SolicitudTransferencia s = ordenes.get(i);
                String sp = "orden_" + i;
                jdbc.execute("SAVEPOINT " + sp);
                try {
                    long id = procedimientos.transferir(s, Canal.LOTE);
                    jdbc.execute("RELEASE SAVEPOINT " + sp);
                    items.add(new ResultadoItem(i, s.referencia(), EstadoItem.CONFIRMADA, id, null));
                } catch (NegocioException e) {
                    // Tras un error PostgreSQL marca la transacción como abortada; ROLLBACK TO SAVEPOINT
                    // deshace solo esta orden y deja la transacción utilizable de nuevo.
                    jdbc.execute("ROLLBACK TO SAVEPOINT " + sp);
                    items.add(new ResultadoItem(i, s.referencia(), EstadoItem.RECHAZADA, null, e.getMessage()));
                    errores.add(e);
                }
            }
        });
        // Los fallos se registran después del COMMIT (no hace falta una segunda conexión durante el lote).
        int e = 0;
        for (ResultadoItem item : items) {
            if (item.estado() == EstadoItem.RECHAZADA) {
                fallos.registrar(ordenes.get(item.indice()), errores.get(e++));
            }
        }
        int ok = (int) items.stream().filter(i -> i.estado() == EstadoItem.CONFIRMADA).count();
        return new ResultadoLote(Modo.PARCIAL, true, ok, items.size() - ok, items);
    }
}
