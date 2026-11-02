package com.example.base_datos;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifica que los índices existen y que el planificador los usa en las consultas críticas. */
class IndicesTest extends BaseIntegracionTest {

    @Test
    void indicesCreados() {
        List<String> indices = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(indices).contains(
                "idx_cuenta_cliente", "idx_tx_origen_fecha", "idx_tx_destino_fecha",
                "idx_cuenta_no_activa", "idx_auditoria_cuenta", "idx_auditoria_fecha_brin",
                "uq_transaccion_referencia", "cuenta_numero_key");
    }

    @Test
    void elExtractoUsaElIndiceCompuesto() {
        String a = nuevaCuenta("100.00");
        String plan = plan("SELECT monto, tipo, creado_en FROM transaccion " +
                "WHERE cuenta_destino_id = (SELECT id FROM cuenta WHERE numero = '" + a + "') " +
                "ORDER BY creado_en DESC LIMIT 20");
        assertThat(plan).contains("idx_tx_destino_fecha");
    }

    @Test
    void laIdempotenciaUsaElIndiceUnico() {
        String plan = plan("SELECT id FROM transaccion WHERE referencia = '" + java.util.UUID.randomUUID() + "'");
        assertThat(plan).contains("uq_transaccion_referencia");
    }

    /** EXPLAIN con enable_seqscan=off: en tablas pequeñas el planificador preferiría Seq Scan. */
    private String plan(String sql) {
        return jdbc.execute((ConnectionCallback<String>) con -> {
            StringBuilder sb = new StringBuilder();
            try (Statement st = con.createStatement()) {
                st.execute("SET enable_seqscan = off");
                try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) {
                        sb.append(rs.getString(1)).append('\n');
                    }
                } finally {
                    st.execute("RESET enable_seqscan");
                }
            }
            return sb.toString();
        });
    }
}
