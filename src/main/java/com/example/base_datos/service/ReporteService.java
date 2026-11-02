package com.example.base_datos.service;

import com.example.base_datos.domain.TransaccionFallida;
import com.example.base_datos.repository.TransaccionFallidaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ReporteService {

    public record ResumenSistema(BigDecimal saldoTotal, long cuentas, long transacciones, long fallidas,
                                 BigDecimal sumaMovimientosJpa, BigDecimal sumaMovimientosProcedimiento) {
    }

    private final JdbcTemplate jdbc;
    private final ProcedimientosDao procedimientos;
    private final TransaccionFallidaRepository fallidas;

    public ReporteService(JdbcTemplate jdbc, ProcedimientosDao procedimientos, TransaccionFallidaRepository fallidas) {
        this.jdbc = jdbc;
        this.procedimientos = procedimientos;
        this.fallidas = fallidas;
    }

    /**
     * REPEATABLE READ: todas las consultas del reporte ven la MISMA foto (snapshot) de la BD,
     * aunque otras transacciones confirmen cambios mientras el reporte se calcula.
     * Con READ COMMITTED cada SELECT tomaría su propia foto y los totales podrían no cuadrar entre sí.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResumenSistema resumen() {
        BigDecimal total = procedimientos.saldoTotal();
        Long numCuentas = jdbc.queryForObject("SELECT count(*) FROM cuenta", Long.class);
        Long numTx = jdbc.queryForObject("SELECT count(*) FROM transaccion", Long.class);
        Long numFallidas = jdbc.queryForObject("SELECT count(*) FROM transaccion_fallida", Long.class);
        BigDecimal jpa = jdbc.queryForObject(
                "SELECT coalesce(sum(monto),0) FROM transaccion WHERE canal = 'JPA'", BigDecimal.class);
        BigDecimal proc = jdbc.queryForObject(
                "SELECT coalesce(sum(monto),0) FROM transaccion WHERE canal IN ('PROCEDURE','LOTE')", BigDecimal.class);
        return new ResumenSistema(total, numCuentas, numTx, numFallidas, jpa, proc);
    }

    @Transactional(readOnly = true)
    public List<TransaccionFallida> fallidasRecientes(int limite) {
        return fallidas.recientes(PageRequest.of(0, Math.min(Math.max(limite, 1), 200)));
    }

    /** Procesos masivos: el procedimiento gestiona sus propios COMMIT, así que NO debe haber transacción abierta. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProcedimientosDao.ResultadoIntereses aplicarIntereses(BigDecimal tasa, int tamanoLote) {
        return procedimientos.aplicarIntereses(tasa, tamanoLote);
    }
}
