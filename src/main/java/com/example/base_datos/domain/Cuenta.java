package com.example.base_datos.domain;

import com.example.base_datos.exception.CuentaInactivaException;
import com.example.base_datos.exception.MontoInvalidoException;
import com.example.base_datos.exception.SaldoInsuficienteException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "cuenta")
public class Cuenta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String numero;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cliente_id", nullable = false)
    private Cliente cliente;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal saldo = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EstadoCuenta estado = EstadoCuenta.ACTIVA;

    /**
     * Bloqueo OPTIMISTA: Hibernate genera {@code UPDATE ... WHERE id=? AND version=?}.
     * Si otra transacción modificó la fila en medio, se actualizan 0 filas y se lanza
     * OptimisticLockingFailureException (evita la "actualización perdida").
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", insertable = false, updatable = false)
    private Instant actualizadoEn;

    protected Cuenta() {
    }

    public Cuenta(String numero, Cliente cliente) {
        this.numero = numero;
        this.cliente = cliente;
    }

    // ---- Reglas de negocio (primera barrera de consistencia; la segunda es el CHECK del motor) ----

    public void debitar(BigDecimal monto) {
        validarOperable(monto);
        if (saldo.compareTo(monto) < 0) {
            throw new SaldoInsuficienteException(numero, saldo, monto);
        }
        saldo = saldo.subtract(monto);
    }

    public void acreditar(BigDecimal monto) {
        validarOperable(monto);
        saldo = saldo.add(monto);
    }

    public void cerrar() {
        estado = EstadoCuenta.CERRADA;
    }

    private void validarOperable(BigDecimal monto) {
        MontoInvalidoException.validar(monto);
        if (estado != EstadoCuenta.ACTIVA) {
            throw new CuentaInactivaException(numero, estado);
        }
    }

    public Long getId() { return id; }
    public String getNumero() { return numero; }
    public Cliente getCliente() { return cliente; }
    public BigDecimal getSaldo() { return saldo; }
    public EstadoCuenta getEstado() { return estado; }
    public Long getVersion() { return version; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
}
