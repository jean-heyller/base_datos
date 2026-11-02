package com.example.base_datos.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Intento rechazado. Se persiste en una transacción independiente (REQUIRES_NEW). */
@Entity
@Table(name = "transaccion_fallida")
public class TransaccionFallida {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID referencia;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private TipoTransaccion tipo;

    @Column(name = "cuenta_origen", length = 20)
    private String cuentaOrigen;

    @Column(name = "cuenta_destino", length = 20)
    private String cuentaDestino;

    @Column(precision = 15, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false, length = 400)
    private String motivo;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant creadoEn;

    protected TransaccionFallida() {
    }

    public TransaccionFallida(UUID referencia, TipoTransaccion tipo, String cuentaOrigen, String cuentaDestino,
                              BigDecimal monto, String motivo) {
        this.referencia = referencia;
        this.tipo = tipo;
        this.cuentaOrigen = cuentaOrigen;
        this.cuentaDestino = cuentaDestino;
        this.monto = monto;
        this.motivo = motivo.length() > 400 ? motivo.substring(0, 400) : motivo;
    }

    public Long getId() { return id; }
    public UUID getReferencia() { return referencia; }
    public TipoTransaccion getTipo() { return tipo; }
    public String getCuentaOrigen() { return cuentaOrigen; }
    public String getCuentaDestino() { return cuentaDestino; }
    public BigDecimal getMonto() { return monto; }
    public String getMotivo() { return motivo; }
    public Instant getCreadoEn() { return creadoEn; }
}
