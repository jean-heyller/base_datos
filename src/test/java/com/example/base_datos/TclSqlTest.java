package com.example.base_datos;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCL directamente en el RDBMS (sin Spring): BEGIN, SAVEPOINT, ROLLBACK TO SAVEPOINT,
 * RELEASE SAVEPOINT, COMMIT, ROLLBACK, SET TRANSACTION ISOLATION LEVEL.
 */
class TclSqlTest extends BaseIntegracionTest {

    @Test
    void sentenciasTclEnSql() throws Exception {
        String a = nuevaCuenta("100.00");
        try (Connection c = conexionIndependiente(); Statement st = c.createStatement()) {
            c.setAutoCommit(true);   // controlamos la transacción con SQL explícito

            st.execute("BEGIN");
            st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            st.execute("UPDATE cuenta SET saldo = saldo - 10 WHERE numero = '" + a + "'");   // 90
            st.execute("SAVEPOINT antes_del_error");
            st.execute("UPDATE cuenta SET saldo = saldo - 50 WHERE numero = '" + a + "'");   // 40
            st.execute("ROLLBACK TO SAVEPOINT antes_del_error");                              // vuelve a 90
            st.execute("SAVEPOINT segundo");
            st.execute("UPDATE cuenta SET saldo = saldo - 5 WHERE numero = '" + a + "'");    // 85
            st.execute("RELEASE SAVEPOINT segundo");
            st.execute("COMMIT");

            assertThat(saldo(a)).isEqualByComparingTo("85.00");

            st.execute("BEGIN");
            st.execute("UPDATE cuenta SET saldo = 0 WHERE numero = '" + a + "'");
            st.execute("ROLLBACK");
            assertThat(saldo(a)).isEqualByComparingTo("85.00");
        }
    }

    @Test
    void savepointTrasErrorRecuperaLaTransaccion() throws Exception {
        String a = nuevaCuenta("100.00");
        try (Connection c = conexionIndependiente(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.executeUpdate("UPDATE cuenta SET saldo = saldo + 1 WHERE numero = '" + a + "'");
            Savepoint sp = c.setSavepoint("sp1");
            try {
                // viola el CHECK: PostgreSQL deja la transacción en estado "aborted"...
                st.executeUpdate("UPDATE cuenta SET saldo = -1 WHERE numero = '" + a + "'");
            } catch (Exception esperado) {
                c.rollback(sp);   // ...y ROLLBACK TO SAVEPOINT la vuelve utilizable
            }
            try (ResultSet rs = st.executeQuery("SELECT saldo FROM cuenta WHERE numero = '" + a + "'")) {
                rs.next();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("101.00"));
            }
            c.commit();
        }
        assertThat(saldo(a)).isEqualByComparingTo("101.00");
    }

    @Test
    void procedimientoAlmacenadoParticipaEnLaTransaccionDelLlamador() throws Exception {
        String a = nuevaCuenta("100.00");
        String b = nuevaCuenta("0");
        try (Connection c = conexionIndependiente(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("CALL sp_transferir('" + a + "', '" + b + "', 40, gen_random_uuid())");
            c.rollback();   // el llamador decide: ROLLBACK deshace lo que hizo el procedimiento
        }
        assertThat(saldo(a)).isEqualByComparingTo("100.00");
        assertThat(saldo(b)).isEqualByComparingTo("0");
    }
}
