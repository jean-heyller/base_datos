package com.example.base_datos;

import com.example.base_datos.service.Comprobante;
import com.example.base_datos.service.LoteService;
import com.example.base_datos.service.SolicitudTransferencia;
import com.example.base_datos.web.dto.Peticiones.Estrategia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotenciaYLotesTest extends BaseIntegracionTest {

    @ParameterizedTest
    @EnumSource(Estrategia.class)
    void reintentoConLaMismaReferenciaNoDuplicaElMovimiento(Estrategia estrategia) {
        String a = nuevaCuenta("100.00");
        String b = nuevaCuenta("0");
        SolicitudTransferencia s = SolicitudTransferencia.de(a, b, "25.00");

        Comprobante primero = transferir(estrategia, s);
        Comprobante segundo = transferir(estrategia, s);   // p.ej. el cliente no recibió la respuesta y reintenta

        assertThat(segundo.id()).isEqualTo(primero.id());
        assertThat(transaccionesConReferencia(s.referencia())).isEqualTo(1);
        assertThat(saldo(a)).isEqualByComparingTo("75.00");
    }

    @Test
    void loteParcialConSavepointsConfirmaLasValidas() {
        String a = nuevaCuenta("100.00");
        String b = nuevaCuenta("0");
        List<SolicitudTransferencia> ordenes = List.of(
                SolicitudTransferencia.de(a, b, "10.00"),
                SolicitudTransferencia.de(a, b, "999.00"),        // saldo insuficiente
                SolicitudTransferencia.de(a, b, "20.00"),
                SolicitudTransferencia.de(a, "NO-EXISTE", "1.00"),// cuenta inexistente
                SolicitudTransferencia.de(a, b, "30.00"));

        LoteService.ResultadoLote r = lotes.procesar(ordenes, LoteService.Modo.PARCIAL);

        assertThat(r.confirmado()).isTrue();
        assertThat(r.confirmadas()).isEqualTo(3);
        assertThat(r.rechazadas()).isEqualTo(2);
        assertThat(saldo(a)).isEqualByComparingTo("40.00");
        assertThat(saldo(b)).isEqualByComparingTo("60.00");
        assertThat(fallidasConReferencia(ordenes.get(1).referencia())).isEqualTo(1);
        assertThat(fallidasConReferencia(ordenes.get(3).referencia())).isEqualTo(1);
        // Los 3 movimientos válidos se confirmaron en UNA sola transacción
        Long txids = jdbc.queryForObject("""
                SELECT count(DISTINCT a.txid) FROM auditoria_saldo a JOIN cuenta c ON c.id = a.cuenta_id
                WHERE c.numero = ? AND a.saldo_nuevo < a.saldo_anterior""", Long.class, a);
        assertThat(txids).isEqualTo(1);
    }
}
