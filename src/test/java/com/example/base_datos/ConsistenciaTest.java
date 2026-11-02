package com.example.base_datos;

import com.example.base_datos.exception.MismaCuentaException;
import com.example.base_datos.exception.MontoInvalidoException;
import com.example.base_datos.exception.NegocioException;
import com.example.base_datos.service.SolicitudTransferencia;
import com.example.base_datos.web.dto.Peticiones.Estrategia;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C de ACID: toda transacción lleva la BD de un estado válido a otro estado válido. */
class ConsistenciaTest extends BaseIntegracionTest {

    @Test
    void elMotorRechazaSaldoNegativoAunqueSeSalteLaAplicacion() throws SQLException {
        String a = nuevaCuenta("10.00");
        try (Connection c = conexionIndependiente(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("UPDATE cuenta SET saldo = -1 WHERE numero = '" + a + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_cuenta_saldo_no_negativo");
        }
        assertThat(saldo(a)).isEqualByComparingTo("10.00");
    }

    @Test
    void elMotorRechazaMovimientosMalFormados() {
        String a = nuevaCuenta("0");
        Long id = jdbc.queryForObject("SELECT id FROM cuenta WHERE numero = ?", Long.class, a);
        // Transferencia de una cuenta a sí misma, monto 0: viola ck_tx_cuentas_distintas / ck_tx_monto_positivo
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transaccion (referencia, tipo, canal, cuenta_origen_id, cuenta_destino_id, monto)
                VALUES (gen_random_uuid(), 'TRANSFERENCIA', 'JPA', ?, ?, 0)""", id, id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void validacionesDeNegocioEnAmbasCapas() {
        String a = nuevaCuenta("10.00");
        String b = nuevaCuenta("0");
        for (Estrategia e : Estrategia.values()) {
            assertThatThrownBy(() -> transferir(e, SolicitudTransferencia.de(a, b, "0.001")))
                    .isInstanceOf(MontoInvalidoException.class);
            assertThatThrownBy(() -> transferir(e, SolicitudTransferencia.de(a, b, "-5")))
                    .isInstanceOf(MontoInvalidoException.class);
            assertThatThrownBy(() -> transferir(e, SolicitudTransferencia.de(a, a, "1")))
                    .isInstanceOf(MismaCuentaException.class);
        }
        assertThat(saldo(a)).isEqualByComparingTo("10.00");
    }

    /**
     * Invariante global: las transferencias solo MUEVEN dinero, nunca lo crean ni lo destruyen.
     * 300 transferencias aleatorias concurrentes (mezclando las 3 estrategias), muchas de ellas
     * fallidas por saldo insuficiente: el total del sistema debe quedar exactamente igual.
     */
    @Test
    void elDineroTotalSeConservaBajoCargaConcurrente() throws Exception {
        List<String> cs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            cs.add(nuevaCuenta("1000.00"));
        }
        BigDecimal totalAntes = procedimientos.saldoTotal();

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rechazadas = new AtomicInteger();
        List<Callable<Object>> tareas = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            tareas.add(Executors.callable(() -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                String o = cs.get(r.nextInt(cs.size()));
                String d;
                do {
                    d = cs.get(r.nextInt(cs.size()));
                } while (d.equals(o));
                BigDecimal monto = BigDecimal.valueOf(r.nextInt(1, 60000), 2);   // 0.01 .. 599.99
                Estrategia e = Estrategia.values()[r.nextInt(3)];
                try {
                    transferir(e, new SolicitudTransferencia(o, d, monto, null, null));
                    ok.incrementAndGet();
                } catch (NegocioException ex) {
                    rechazadas.incrementAndGet();
                }
            }));
        }
        concurrente(8, tareas);

        assertThat(ok.get() + rechazadas.get()).isEqualTo(300);
        assertThat(ok.get()).isPositive();
        assertThat(procedimientos.saldoTotal()).isEqualByComparingTo(totalAntes);
        BigDecimal sumaCuentas = cs.stream().map(this::saldo).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumaCuentas).isEqualByComparingTo("6000.00");
        cs.forEach(c -> assertThat(saldo(c)).isNotNegative());
    }
}
