package com.example.base_datos.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Movimiento confirmado del libro contable (append-only). */
@Entity
@Table(name = "transaccion")
public class Transaccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID referencia;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private TipoTransaccion tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Canal canal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_origen_id")
    private Cuenta cuentaOrigen;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_destino_id")
    private Cuenta cuentaDestino;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal monto;

    @Column(length = 200)
    private String descripcion;

    /** Lo asigna el motor (DEFAULT now() = hora de inicio de la transacción); Hibernate lo relee tras el INSERT. */
    @Generated
    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant creadoEn;

    protected Transaccion() {
    }

    private Transaccion(UUID referencia, TipoTransaccion tipo, Canal canal, Cuenta origen, Cuenta destino,
                        BigDecimal monto, String descripcion) {
        this.referencia = referencia;
        this.tipo = tipo;
        this.canal = canal;
        this.cuentaOrigen = origen;
        this.cuentaDestino = destino;
        this.monto = monto;
        this.descripcion = descripcion;
    }

    public static Transaccion transferencia(UUID ref, Canal canal, Cuenta origen, Cuenta destino,
                                            BigDecimal monto, String descripcion) {
        return new Transaccion(ref, TipoTransaccion.TRANSFERENCIA, canal, origen, destino, monto, descripcion);
    }

    public static Transaccion deposito(UUID ref, Canal canal, Cuenta destino, BigDecimal monto) {
        return new Transaccion(ref, TipoTransaccion.DEPOSITO, canal, null, destino, monto, null);
    }

    public Long getId() { return id; }
    public UUID getReferencia() { return referencia; }
    public TipoTransaccion getTipo() { return tipo; }
    public Canal getCanal() { return canal; }
    public Cuenta getCuentaOrigen() { return cuentaOrigen; }
    public Cuenta getCuentaDestino() { return cuentaDestino; }
    public BigDecimal getMonto() { return monto; }
    public String getDescripcion() { return descripcion; }
    public Instant getCreadoEn() { return creadoEn; }
}
