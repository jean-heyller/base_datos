package com.example.base_datos.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "cliente")
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String documento;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant creadoEn;

    protected Cliente() {
    }

    public Cliente(String documento, String nombre, String email) {
        this.documento = documento;
        this.nombre = nombre;
        this.email = email;
    }

    public Long getId() { return id; }
    public String getDocumento() { return documento; }
    public String getNombre() { return nombre; }
    public String getEmail() { return email; }
    public Instant getCreadoEn() { return creadoEn; }
}
