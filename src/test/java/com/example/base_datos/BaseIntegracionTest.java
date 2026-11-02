package com.example.base_datos;

import com.example.base_datos.config.EmbeddedPostgresConfig;
import com.example.base_datos.service.*;
import com.example.base_datos.web.dto.Peticiones.Estrategia;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Todos los tests corren contra un PostgreSQL REAL (embebido), no contra H2:
 * los procedimientos PL/pgSQL, FOR UPDATE, SERIALIZABLE y los CHECK se comportan como en producción.
 * Cada test crea sus propias cuentas con números aleatorios, así no interfieren entre sí.
 */
@SpringBootTest(properties = {
        "banco.reintentos.maximos=40",          // alta contención en tests de concurrencia optimista
        "banco.reintentos.espera-inicial-ms=5"
})
@ActiveProfiles("embedded")
public abstract class BaseIntegracionTest {

    @Autowired protected TransferenciaService transferencias;
    @Autowired protected CuentaService cuentas;
    @Autowired protected LoteService lotes;
    @Autowired protected ReporteService reportes;
    @Autowired protected ProcedimientosDao procedimientos;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;

    protected String nuevaCuenta(String saldoInicial) {
        String sufijo = Long.toString(ThreadLocalRandom.current().nextLong(1L << 40, 1L << 50), 36).toUpperCase();
        String numero = "T-" + sufijo;
        cuentas.abrirCuenta("D" + sufijo, "Cliente " + sufijo, sufijo.toLowerCase() + "@test.co", numero);
        BigDecimal saldo = new BigDecimal(saldoInicial);
        if (saldo.signum() > 0) {
            transferencias.depositar(numero, saldo, null);
        }
        return numero;
    }

    protected BigDecimal saldo(String numero) {
        return jdbc.queryForObject("SELECT saldo FROM cuenta WHERE numero = ?", BigDecimal.class, numero);
    }

    protected long transaccionesConReferencia(UUID ref) {
        return jdbc.queryForObject("SELECT count(*) FROM transaccion WHERE referencia = ?", Long.class, ref);
    }

    protected long fallidasConReferencia(UUID ref) {
        return jdbc.queryForObject("SELECT count(*) FROM transaccion_fallida WHERE referencia = ?", Long.class, ref);
    }

    protected Comprobante transferir(Estrategia estrategia, SolicitudTransferencia s) {
        return switch (estrategia) {
            case PESIMISTA -> transferencias.transferir(s);
            case OPTIMISTA -> transferencias.transferirOptimista(s);
            case PROCEDIMIENTO -> transferencias.transferirConProcedimiento(s);
        };
    }

    /** Conexión física NUEVA, fuera del pool de Hikari y de cualquier transacción de Spring. */
    protected Connection conexionIndependiente() throws SQLException {
        return DriverManager.getConnection(EmbeddedPostgresConfig.jdbcUrl(dataSource), "postgres", "postgres");
    }

    /**
     * Ejecuta las tareas en paralelo y ESPERA a que terminen todas (invokeAll) antes de propagar el
     * primer error. Así ningún hilo sigue vivo interfiriendo con el siguiente test.
     */
    protected static <T> void concurrente(int hilos, List<Callable<T>> tareas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        try {
            List<Future<T>> fs = pool.invokeAll(tareas, 120, TimeUnit.SECONDS);
            for (Future<T> f : fs) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    protected static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
