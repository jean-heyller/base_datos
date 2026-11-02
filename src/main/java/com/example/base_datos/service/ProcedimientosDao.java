package com.example.base_datos.service;

import com.example.base_datos.domain.Canal;
import com.example.base_datos.exception.TraductorErroresBd;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Acceso a los procedimientos almacenados / funciones de PostgreSQL.
 * <p>
 * JdbcTemplate usa la MISMA conexión que la transacción JPA activa (JpaTransactionManager la expone),
 * por eso un CALL dentro de un método {@code @Transactional} participa en su COMMIT/ROLLBACK.
 */
@Repository
public class ProcedimientosDao {

    private final JdbcTemplate jdbc;

    public ProcedimientosDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** CALL sp_transferir(...): el parámetro INOUT p_tx_id vuelve como una fila de resultado. */
    public long transferir(SolicitudTransferencia s, Canal canal) {
        try {
            Long id = jdbc.queryForObject(
                    "CALL sp_transferir(?::varchar, ?::varchar, ?::numeric, ?::uuid, ?::varchar, ?::varchar, NULL::bigint)",
                    Long.class,
                    s.origen(), s.destino(), s.monto(), s.referencia(), s.descripcion(), canal.name());
            return id;
        } catch (DataAccessException e) {
            throw TraductorErroresBd.traducir(e);
        }
    }

    public long depositar(String numero, BigDecimal monto, UUID referencia) {
        try {
            Long id = jdbc.queryForObject(
                    "CALL sp_depositar(?::varchar, ?::numeric, ?::uuid, NULL::bigint)",
                    Long.class, numero, monto, referencia);
            return id;
        } catch (DataAccessException e) {
            throw TraductorErroresBd.traducir(e);
        }
    }

    public BigDecimal saldoTotal() {
        return jdbc.queryForObject("SELECT fn_saldo_total()", BigDecimal.class);
    }

    public List<MovimientoExtracto> extracto(String numero, int limite) {
        return jdbc.query("SELECT * FROM fn_extracto(?, ?)",
                (rs, i) -> new MovimientoExtracto(
                        rs.getLong("transaccion_id"),
                        rs.getString("tipo"),
                        rs.getBigDecimal("movimiento"),
                        rs.getString("contraparte"),
                        toInstant(rs.getTimestamp("fecha"))),
                numero, limite);
    }

    /**
     * sp_aplicar_intereses hace COMMIT por lotes DENTRO del procedimiento. PostgreSQL solo lo permite
     * si el CALL no está dentro de un bloque BEGIN, así que se ejecuta con autocommit=true
     * (el método de servicio que lo invoca usa Propagation.NOT_SUPPORTED).
     */
    public ResultadoIntereses aplicarIntereses(BigDecimal tasa, int tamanoLote) {
        try {
            return jdbc.execute((ConnectionCallback<ResultadoIntereses>) con -> {
                boolean autocommitPrevio = con.getAutoCommit();
                con.setAutoCommit(true);
                try (PreparedStatement ps = con.prepareStatement(
                        "CALL sp_aplicar_intereses(?::numeric, ?::int, NULL::int, NULL::int)")) {
                    ps.setBigDecimal(1, tasa);
                    ps.setInt(2, tamanoLote);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return new ResultadoIntereses(rs.getInt("p_procesadas"), rs.getInt("p_lotes"));
                    }
                } finally {
                    con.setAutoCommit(autocommitPrevio);
                }
            });
        } catch (DataAccessException e) {
            throw TraductorErroresBd.traducir(e);
        }
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record MovimientoExtracto(long transaccionId, String tipo, BigDecimal movimiento,
                                     String contraparte, java.time.Instant fecha) {
    }

    public record ResultadoIntereses(int cuentasProcesadas, int lotesConfirmados) {
    }
}
