package com.example.base_datos;

import com.example.base_datos.exception.SaldoInsuficienteException;
import com.example.base_datos.service.LoteService;
import com.example.base_datos.service.SolicitudTransferencia;
import com.example.base_datos.web.dto.Peticiones.Estrategia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A de ACID: la transacción se ejecuta completa o no se ejecuta en absoluto. */
class AtomicidadTest extends BaseIntegracionTest {

    @ParameterizedTest
    @EnumSource(Estrategia.class)
    void transferenciaFallidaNoDejaRastroParcial(Estrategia estrategia) {
        String a = nuevaCuenta("100.00");
        String b = nuevaCuenta("0");
        long auditoriasAntes = jdbc.queryForObject("SELECT count(*) FROM auditoria_saldo", Long.class);
        SolicitudTransferencia s = new SolicitudTransferencia(a, b, bd("150.00"), null, "excede saldo");

        assertThatThrownBy(() -> transferir(estrategia, s)).isInstanceOf(SaldoInsuficienteException.class);

        // Ni débito, ni crédito, ni movimiento, ni auditoría: ROLLBACK completo
        assertThat(saldo(a)).isEqualByComparingTo("100.00");
        assertThat(saldo(b)).isEqualByComparingTo("0");
        assertThat(transaccionesConReferencia(s.referencia())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_saldo", Long.class)).isEqualTo(auditoriasAntes);

        // ... pero el registro del fallo (REQUIRES_NEW, transacción independiente) sí es durable
        assertThat(fallidasConReferencia(s.referencia())).isEqualTo(1);
    }

    @Test
    void loteTodoONadaRevierteLasOrdenesYaEjecutadas() {
        String a = nuevaCuenta("100.00");
        String b = nuevaCuenta("0");
        String c = nuevaCuenta("0");
        List<SolicitudTransferencia> ordenes = List.of(
                SolicitudTransferencia.de(a, b, "30.00"),
                SolicitudTransferencia.de(a, c, "30.00"),
                SolicitudTransferencia.de(a, b, "500.00"),  // falla: saldo insuficiente
                SolicitudTransferencia.de(a, c, "1.00"));

        LoteService.ResultadoLote r = lotes.procesar(ordenes, LoteService.Modo.TODO_O_NADA);

        assertThat(r.confirmado()).isFalse();
        assertThat(r.items()).extracting(LoteService.ResultadoItem::estado).containsExactly(
                LoteService.EstadoItem.REVERTIDA, LoteService.EstadoItem.REVERTIDA,
                LoteService.EstadoItem.RECHAZADA, LoteService.EstadoItem.NO_PROCESADA);
        assertThat(saldo(a)).isEqualByComparingTo("100.00");
        assertThat(saldo(b)).isEqualByComparingTo("0");
        assertThat(saldo(c)).isEqualByComparingTo("0");
        ordenes.forEach(o -> assertThat(transaccionesConReferencia(o.referencia())).isZero());
    }

    @Test
    void altaDeClienteYCuentaEsUnaUnidad() {
        String existente = nuevaCuenta("0");
        // El cliente es nuevo pero el número de cuenta está repetido -> falla el INSERT de cuenta
        assertThatThrownBy(() -> cuentas.abrirCuenta("DOC-HUERFANO", "Nadie", "huerfano@test.co", existente))
                .isInstanceOf(DataIntegrityViolationException.class);
        // El INSERT del cliente (hecho antes en la misma transacción) también se revirtió
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cliente WHERE documento = 'DOC-HUERFANO'", Long.class))
                .isZero();
    }

    @Test
    void errorEnElProcedimientoRevierteTambienLoHechoAntesEnLaMismaTransaccion() {
        String a = nuevaCuenta("50.00");
        String b = nuevaCuenta("0");
        BigDecimal antes = saldo(a);
        // Dentro de un lote TODO_O_NADA la 1ª llamada a sp_transferir sí modificó filas; la 2ª lanza TX002
        LoteService.ResultadoLote r = lotes.procesar(List.of(
                SolicitudTransferencia.de(a, b, "10.00"),
                SolicitudTransferencia.de(a, "NO-EXISTE", "10.00")), LoteService.Modo.TODO_O_NADA);
        assertThat(r.confirmado()).isFalse();
        assertThat(saldo(a)).isEqualByComparingTo(antes);
    }
}
