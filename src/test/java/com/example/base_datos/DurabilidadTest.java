package com.example.base_datos;

import com.example.base_datos.service.Comprobante;
import com.example.base_datos.service.ProcedimientosDao;
import com.example.base_datos.service.SolicitudTransferencia;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** D de ACID: una vez confirmada (COMMIT), la transacción persiste aunque falle el sistema. */
class DurabilidadTest extends BaseIntegracionTest {

    @Test
    void elMotorEscribeElWalADiscoEnCadaCommit() throws Exception {
        try (Connection c = conexionIndependiente(); Statement st = c.createStatement()) {
            assertThat(valor(st, "SHOW fsync")).isEqualTo("on");
            assertThat(valor(st, "SHOW synchronous_commit")).isEqualTo("on");
            assertThat(valor(st, "SHOW full_page_writes")).isEqualTo("on");
        }
    }

    @Test
    void loConfirmadoEsVisibleDesdeUnaConexionFisicaNueva() throws Exception {
        String a = nuevaCuenta("300.00");
        String b = nuevaCuenta("0");
        Comprobante comp = transferencias.transferir(SolicitudTransferencia.de(a, b, "120.00"));

        try (Connection c = conexionIndependiente();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT (SELECT saldo FROM cuenta WHERE numero = ?), (SELECT count(*) FROM transaccion WHERE id = ?)")) {
            ps.setString(1, b);
            ps.setLong(2, comp.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("120.00");
                assertThat(rs.getLong(2)).isEqualTo(1);
            }
        }
    }

    @Test
    void loNoConfirmadoSeDescartaAlCaerLaConexion() throws Exception {
        String a = nuevaCuenta("50.00");
        try (Connection c = conexionIndependiente()) {
            c.setAutoCommit(false);                       // BEGIN implícito
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE cuenta SET saldo = 0 WHERE numero = '" + a + "'");
            }
            // se cierra SIN COMMIT: simula una caída del cliente a mitad de transacción
        }
        assertThat(saldo(a)).isEqualByComparingTo("50.00");
    }

    /**
     * sp_aplicar_intereses hace COMMIT dentro del procedimiento cada N cuentas.
     * La auditoría registra txid_current(): cada lote quedó en una transacción distinta y durable.
     */
    @Test
    void procedimientoConfirmaPorLotes() {
        String a = nuevaCuenta("1000.00");
        long ultimaAuditoria = jdbc.queryForObject("SELECT coalesce(max(id),0) FROM auditoria_saldo", Long.class);
        long activasConSaldo = jdbc.queryForObject(
                "SELECT count(*) FROM cuenta WHERE estado = 'ACTIVA' AND saldo > 0", Long.class);

        ProcedimientosDao.ResultadoIntereses r = reportes.aplicarIntereses(bd("0.01"), 3);

        assertThat(r.lotesConfirmados()).isEqualTo((int) Math.ceil(activasConSaldo / 3.0));
        assertThat(r.cuentasProcesadas()).isPositive();
        assertThat(saldo(a)).isEqualByComparingTo("1010.00");
        Long transaccionesDistintas = jdbc.queryForObject(
                "SELECT count(DISTINCT txid) FROM auditoria_saldo WHERE id > ?", Long.class, ultimaAuditoria);
        assertThat(transaccionesDistintas).isGreaterThan(1);
    }

    private static String valor(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
