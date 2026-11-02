package com.example.base_datos.service;

import com.example.base_datos.domain.*;
import com.example.base_datos.exception.CuentaInactivaException;
import com.example.base_datos.exception.CuentaNoEncontradaException;
import com.example.base_datos.exception.MismaCuentaException;
import com.example.base_datos.repository.ClienteRepository;
import com.example.base_datos.repository.CuentaRepository;
import com.example.base_datos.repository.TransaccionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CuentaService {

    public record CuentaVista(String numero, String titular, BigDecimal saldo, EstadoCuenta estado,
                              long version, Instant actualizadaEn) {
        static CuentaVista de(Cuenta c) {
            return new CuentaVista(c.getNumero(), c.getCliente().getNombre(), c.getSaldo(), c.getEstado(),
                    c.getVersion(), c.getActualizadoEn());
        }
    }

    private final ClienteRepository clientes;
    private final CuentaRepository cuentas;
    private final TransaccionRepository transacciones;
    private final ProcedimientosDao procedimientos;
    private final Reintentador reintentador;
    private final TransactionTemplate txSerializable;

    public CuentaService(ClienteRepository clientes, CuentaRepository cuentas, TransaccionRepository transacciones,
                         ProcedimientosDao procedimientos, Reintentador reintentador, PlatformTransactionManager tm) {
        this.clientes = clientes;
        this.cuentas = cuentas;
        this.transacciones = transacciones;
        this.procedimientos = procedimientos;
        this.reintentador = reintentador;
        this.txSerializable = new TransactionTemplate(tm);
        this.txSerializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.txSerializable.setTimeout(10);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long crearCliente(String documento, String nombre, String email) {
        return clientes.save(new Cliente(documento, nombre, email)).getId();
    }

    /** Alta de cliente+cuenta: si la cuenta falla (número duplicado), tampoco queda el cliente (atomicidad). */
    @Transactional(rollbackFor = Exception.class)
    public CuentaVista abrirCuenta(String documentoCliente, String nombreCliente, String emailCliente, String numero) {
        Cliente cliente = clientes.findByDocumento(documentoCliente)
                .orElseGet(() -> clientes.save(new Cliente(documentoCliente, nombreCliente, emailCliente)));
        Cuenta cuenta = cuentas.saveAndFlush(new Cuenta(numero, cliente));
        return CuentaVista.de(cuenta);
    }

    @Transactional(readOnly = true)
    public CuentaVista consultar(String numero) {
        return cuentas.findByNumero(numero).map(CuentaVista::de)
                .orElseThrow(() -> new CuentaNoEncontradaException(numero));
    }

    /** readOnly: Hibernate no hace dirty checking/flush y el driver puede enrutar a réplicas de lectura. */
    @Transactional(readOnly = true)
    public List<ProcedimientosDao.MovimientoExtracto> extracto(String numero, int limite) {
        if (cuentas.findByNumero(numero).isEmpty()) {
            throw new CuentaNoEncontradaException(numero);
        }
        return procedimientos.extracto(numero, Math.min(Math.max(limite, 1), 500));
    }

    /**
     * Cierre de cuenta con aislamiento SERIALIZABLE.
     * Lee el saldo, lo traslada a otra cuenta y marca CERRADA. SERIALIZABLE garantiza que el
     * resultado es equivalente a haber ejecutado las transacciones una tras otra: si un depósito
     * concurrente cambia el saldo leído, PostgreSQL aborta una de las dos con SQLSTATE 40001
     * y el Reintentador repite la transacción completa con el saldo actualizado (nada se pierde).
     */
    public CuentaVista cerrarCuenta(String numero, String cuentaDestino) {
        if (numero.equals(cuentaDestino)) {
            throw new MismaCuentaException(numero);
        }
        return reintentador.ejecutar("cierre-cuenta", () -> txSerializable.execute(status -> {
            Cuenta cuenta = cuentas.findByNumero(numero).orElseThrow(() -> new CuentaNoEncontradaException(numero));
            Cuenta destino = cuentas.findByNumero(cuentaDestino)
                    .orElseThrow(() -> new CuentaNoEncontradaException(cuentaDestino));
            if (cuenta.getEstado() != EstadoCuenta.ACTIVA) {
                throw new CuentaInactivaException(numero, cuenta.getEstado());
            }
            BigDecimal saldo = cuenta.getSaldo();
            if (saldo.signum() > 0) {
                cuenta.debitar(saldo);
                destino.acreditar(saldo);
                transacciones.save(Transaccion.transferencia(UUID.randomUUID(), Canal.JPA, cuenta, destino, saldo,
                        "Cierre de cuenta " + numero));
            }
            cuenta.cerrar();
            cuentas.flush();
            return CuentaVista.de(cuenta);
        }));
    }
}
