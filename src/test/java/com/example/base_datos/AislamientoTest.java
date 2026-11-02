package com.example.base_datos;

import com.example.base_datos.domain.EstadoCuenta;
import com.example.base_datos.exception.CuentaInactivaException;
import com.example.base_datos.exception.NegocioException;
import com.example.base_datos.service.SolicitudTransferencia;
import com.example.base_datos.web.dto.Peticiones.Estrategia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I de ACID: las transacciones concurrentes no interfieren entre sí. */
class AislamientoTest extends BaseIntegracionTest {

    @Autowired
    PlatformTransactionManager tm;

    /**
     * Problema clásico de "actualización perdida": 60 hilos debitan la misma cuenta a la vez.
     * Sin control de concurrencia, dos hilos leerían el mismo saldo y uno pisaría al otro.
     */
    @ParameterizedTest
    @EnumSource(Estrategia.class)
    void sinActualizacionesPerdidas(Estrategia estrategia) throws Exception {
        String a = nuevaCuenta("10000.00");
        String b = nuevaCuenta("0");
        int n = 60;

        List<Callable<Object>> tareas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tareas.add(() -> transferir(estrategia, SolicitudTransferencia.de(a, b, "10.00")));
        }
        concurrente(8, tareas);   // propaga cualquier excepción

        assertThat(saldo(a)).isEqualByComparingTo("9400.00");
        assertThat(saldo(b)).isEqualByComparingTo("600.00");
        Long movimientos = jdbc.queryForObject("""
                SELECT count(*) FROM transaccion t JOIN cuenta c ON c.id = t.cuenta_destino_id
                WHERE c.numero = ? AND t.tipo = 'TRANSFERENCIA'""", Long.class, b);
        assertThat(movimientos).isEqualTo(n);
    }

    /**
     * A->B y B->A simultáneas. Si cada transacción bloqueara primero "su" origen, se formaría un
     * ciclo de espera (deadlock). Bloquear siempre en orden de id lo evita por construcción.
     */
    @ParameterizedTest
    @EnumSource(value = Estrategia.class, names = {"PESIMISTA", "PROCEDIMIENTO"})
    void transferenciasCruzadasSinDeadlock(Estrategia estrategia) throws Exception {
        String a = nuevaCuenta("1000.00");
        String b = nuevaCuenta("1000.00");
        List<Callable<Object>> tareas = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tareas.add(() -> transferir(estrategia, SolicitudTransferencia.de(a, b, "5.00")));
            tareas.add(() -> transferir(estrategia, SolicitudTransferencia.de(b, a, "3.00")));
        }
        concurrente(8, tareas);

        assertThat(saldo(a)).isEqualByComparingTo("920.00");    // 1000 - 40*5 + 40*3
        assertThat(saldo(b)).isEqualByComparingTo("1080.00");
    }

    /** REPEATABLE READ: dentro de la transacción, releer da el mismo valor aunque otro haga COMMIT. */
    @Test
    void repeatableReadMantieneLaFotoInicial() throws Exception {
        String a = nuevaCuenta("100.00");
        TransactionTemplate rr = new TransactionTemplate(tm);
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        rr.setReadOnly(true);

        BigDecimal[] lecturas = rr.execute(status -> {
            BigDecimal primera = saldo(a);
            depositoConfirmadoEnOtroHilo(a, "50.00");
            BigDecimal segunda = saldo(a);
            return new BigDecimal[]{primera, segunda};
        });

        assertThat(lecturas[0]).isEqualByComparingTo("100.00");
        assertThat(lecturas[1]).isEqualByComparingTo("100.00");   // misma foto
        assertThat(saldo(a)).isEqualByComparingTo("150.00");      // fuera de la transacción ya se ve
    }

    /** READ COMMITTED (por defecto): cada sentencia ve lo último confirmado -> lectura no repetible. */
    @Test
    void readCommittedPermiteLecturaNoRepetible() throws Exception {
        String a = nuevaCuenta("100.00");
        TransactionTemplate rc = new TransactionTemplate(tm);
        rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        BigDecimal[] lecturas = rc.execute(status -> {
            BigDecimal primera = saldo(a);
            depositoConfirmadoEnOtroHilo(a, "50.00");
            return new BigDecimal[]{primera, saldo(a)};
        });

        assertThat(lecturas[0]).isEqualByComparingTo("100.00");
        assertThat(lecturas[1]).isEqualByComparingTo("150.00");
    }

    /** Ningún nivel permite lecturas sucias: lo no confirmado es invisible para los demás. */
    @Test
    void noHayLecturasSucias() throws Exception {
        String a = nuevaCuenta("100.00");
        CountDownLatch escrito = new CountDownLatch(1);
        CountDownLatch leido = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(tm);

        Future<?> escritor = Executors.newSingleThreadExecutor().submit(() -> tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE cuenta SET saldo = 999999 WHERE numero = ?", a);
            escrito.countDown();
            await(leido);
            status.setRollbackOnly();   // ROLLBACK
        }));

        await(escrito);
        assertThat(saldo(a)).isEqualByComparingTo("100.00");   // no ve el UPDATE pendiente
        leido.countDown();
        escritor.get(10, TimeUnit.SECONDS);
        assertThat(saldo(a)).isEqualByComparingTo("100.00");
    }

    /**
     * SERIALIZABLE + reintentos: cierre de cuenta mientras llegan depósitos concurrentes.
     * Cada depósito o bien entra ANTES del cierre (y su dinero se traslada a la cuenta destino),
     * o bien llega DESPUÉS y es rechazado por cuenta inactiva. Nunca se pierde dinero.
     */
    @Test
    void cierreSerializableConDepositosConcurrentes() throws Exception {
        String x = nuevaCuenta("100.00");
        String destino = nuevaCuenta("0");
        AtomicInteger aceptados = new AtomicInteger();
        List<Callable<Object>> tareas = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            if (i == 10) {
                tareas.add(() -> cuentas.cerrarCuenta(x, destino));
            }
            tareas.add(Executors.callable(() -> {
                try {
                    transferencias.depositar(x, bd("10.00"), null);
                    aceptados.incrementAndGet();
                } catch (CuentaInactivaException e) {
                    // llegó después del cierre: rechazo correcto
                }
            }));
        }
        concurrente(6, tareas);

        assertThat(cuentas.consultar(x).estado()).isEqualTo(EstadoCuenta.CERRADA);
        assertThat(saldo(x)).isEqualByComparingTo("0");
        assertThat(saldo(destino)).isEqualByComparingTo(
                bd("100.00").add(bd("10.00").multiply(BigDecimal.valueOf(aceptados.get()))));
        assertThatThrownBy(() -> transferencias.transferir(SolicitudTransferencia.de(destino, x, "1.00")))
                .isInstanceOf(NegocioException.class);
    }

    private void depositoConfirmadoEnOtroHilo(String numero, String monto) {
        try {
            // Otro hilo = otra conexión y otra transacción, que hace COMMIT antes de volver
            Executors.newSingleThreadExecutor()
                    .submit(() -> transferencias.depositar(numero, bd(monto), null))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch l) {
        try {
            assertThat(l.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
