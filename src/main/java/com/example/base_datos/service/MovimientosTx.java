package com.example.base_datos.service;

import com.example.base_datos.domain.Canal;
import com.example.base_datos.domain.Cuenta;
import com.example.base_datos.domain.TipoTransaccion;
import com.example.base_datos.domain.Transaccion;
import com.example.base_datos.exception.CuentaNoEncontradaException;
import com.example.base_datos.exception.MismaCuentaException;
import com.example.base_datos.exception.MontoInvalidoException;
import com.example.base_datos.exception.NegocioException;
import com.example.base_datos.repository.CuentaRepository;
import com.example.base_datos.repository.TransaccionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Unidades transaccionales (cada método público = 1 transacción: BEGIN ... COMMIT | ROLLBACK).
 * No se invocan directamente desde fuera: {@link TransferenciaService} las envuelve con reintentos,
 * porque un reintento debe repetir la transacción COMPLETA y no ejecutarse dentro de ella.
 */
@Service
public class MovimientosTx {

    private final CuentaRepository cuentas;
    private final TransaccionRepository transacciones;
    private final ProcedimientosDao procedimientos;
    private final RegistroFallosService fallos;

    public MovimientosTx(CuentaRepository cuentas, TransaccionRepository transacciones,
                         ProcedimientosDao procedimientos, RegistroFallosService fallos) {
        this.cuentas = cuentas;
        this.transacciones = transacciones;
        this.procedimientos = procedimientos;
        this.fallos = fallos;
    }

    // =====================================================================================
    // 1) DECLARATIVA + BLOQUEO PESIMISTA
    //    BEGIN;
    //    SELECT * FROM cuenta WHERE numero IN (?,?) ORDER BY id FOR NO KEY UPDATE;
    //    INSERT INTO transaccion ...; UPDATE cuenta ...; UPDATE cuenta ...;
    //    COMMIT;   (o ROLLBACK si se lanza cualquier excepción)
    // =====================================================================================
    @Transactional(propagation = Propagation.REQUIRED,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class,
            timeout = 10)
    public Comprobante transferirPesimista(SolicitudTransferencia s) {
        try {
            validar(s);
            Optional<Transaccion> previa = transacciones.findByReferencia(s.referencia());
            if (previa.isPresent()) {
                return Comprobante.de(previa.get());          // idempotente
            }

            Map<String, Cuenta> bloqueadas = cuentas.bloquearPorNumeros(List.of(s.origen(), s.destino()))
                    .stream().collect(Collectors.toMap(Cuenta::getNumero, Function.identity()));
            Cuenta origen = requerir(bloqueadas, s.origen());
            Cuenta destino = requerir(bloqueadas, s.destino());

            origen.debitar(s.monto());     // puede lanzar SaldoInsuficienteException -> ROLLBACK
            destino.acreditar(s.monto());

            // Los UPDATE de cuenta se emiten en el flush (dirty checking), justo antes del COMMIT.
            Transaccion tx = transacciones.save(
                    Transaccion.transferencia(s.referencia(), Canal.JPA, origen, destino, s.monto(), s.descripcion()));
            return Comprobante.de(tx);
        } catch (NegocioException e) {
            fallos.registrar(s, e);        // REQUIRES_NEW: sobrevive al ROLLBACK de esta transacción
            throw e;                       // RuntimeException -> Spring hace ROLLBACK
        }
    }

    // =====================================================================================
    // 2) BLOQUEO OPTIMISTA (@Version)
    //    Sin locks al leer. En el flush:
    //    UPDATE cuenta SET saldo=?, version=v+1 WHERE id=? AND version=v
    //    Si otra transacción confirmó antes => 0 filas => OptimisticLockingFailureException => ROLLBACK
    // =====================================================================================
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 10)
    public Comprobante transferirOptimista(SolicitudTransferencia s) {
        try {
            validar(s);
            Optional<Transaccion> previa = transacciones.findByReferencia(s.referencia());
            if (previa.isPresent()) {
                return Comprobante.de(previa.get());
            }
            Cuenta origen = cuentas.findByNumero(s.origen())
                    .orElseThrow(() -> new CuentaNoEncontradaException(s.origen()));
            Cuenta destino = cuentas.findByNumero(s.destino())
                    .orElseThrow(() -> new CuentaNoEncontradaException(s.destino()));
            origen.debitar(s.monto());
            destino.acreditar(s.monto());
            // Flush de los UPDATE ... WHERE version=? ANTES del INSERT del movimiento: el conflicto de
            // versión aflora aquí, sin haber tomado aún los KEY SHARE de las FKs de transaccion.
            cuentas.flush();
            Transaccion tx = transacciones.save(
                    Transaccion.transferencia(s.referencia(), Canal.JPA, origen, destino, s.monto(), s.descripcion()));
            return Comprobante.de(tx);
        } catch (NegocioException e) {
            fallos.registrar(s, e);
            throw e;
        }
    }

    // =====================================================================================
    // 3) PROCEDIMIENTO ALMACENADO dentro de la transacción de Spring
    //    BEGIN; CALL sp_transferir(...); SELECT ... FROM transaccion; COMMIT
    // =====================================================================================
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Comprobante transferirProcedimiento(SolicitudTransferencia s) {
        try {
            long id = procedimientos.transferir(s, Canal.PROCEDURE);
            return Comprobante.de(transacciones.findById(id).orElseThrow());
        } catch (NegocioException e) {
            fallos.registrar(s, e);
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Comprobante depositar(String numero, BigDecimal monto, UUID referencia) {
        try {
            MontoInvalidoException.validar(monto);
            Optional<Transaccion> previa = transacciones.findByReferencia(referencia);
            if (previa.isPresent()) {
                return Comprobante.de(previa.get());
            }
            Cuenta cuenta = cuentas.bloquearPorNumeros(List.of(numero)).stream().findFirst()
                    .orElseThrow(() -> new CuentaNoEncontradaException(numero));
            cuenta.acreditar(monto);
            return Comprobante.de(transacciones.save(Transaccion.deposito(referencia, Canal.JPA, cuenta, monto)));
        } catch (NegocioException e) {
            fallos.registrar(referencia, TipoTransaccion.DEPOSITO, null, numero, monto, e.getMessage());
            throw e;
        }
    }

    private static void validar(SolicitudTransferencia s) {
        MontoInvalidoException.validar(s.monto());
        if (s.origen() == null || s.destino() == null) {
            throw new CuentaNoEncontradaException("(no indicada)");
        }
        if (s.origen().equals(s.destino())) {
            throw new MismaCuentaException(s.origen());
        }
    }

    private static Cuenta requerir(Map<String, Cuenta> mapa, String numero) {
        Cuenta c = mapa.get(numero);
        if (c == null) {
            throw new CuentaNoEncontradaException(numero);
        }
        return c;
    }
}
