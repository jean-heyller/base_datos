package com.example.base_datos.repository;

import com.example.base_datos.domain.Transaccion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransaccionRepository extends JpaRepository<Transaccion, Long> {

    /** Idempotencia: usa el índice UNIQUE uq_transaccion_referencia. */
    Optional<Transaccion> findByReferencia(UUID referencia);

    long countByReferencia(UUID referencia);
}
