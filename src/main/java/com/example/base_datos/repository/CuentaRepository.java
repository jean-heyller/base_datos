package com.example.base_datos.repository;

import com.example.base_datos.domain.Cuenta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CuentaRepository extends JpaRepository<Cuenta, Long> {

    /** Lectura sin bloqueo (usa el índice UNIQUE de cuenta.numero). */
    Optional<Cuenta> findByNumero(String numero);

    /**
     * Bloqueo PESIMISTA de fila hasta el COMMIT/ROLLBACK.
     * <ul>
     *   <li>{@code ORDER BY id}: todas las transacciones adquieren los locks en el mismo orden,
     *       lo que elimina los deadlocks A->B / B->A.</li>
     *   <li>{@code FOR NO KEY UPDATE} (y no FOR UPDATE): como no modificamos la clave, no hace falta
     *       bloquear los {@code KEY SHARE} que toman las FKs al insertar en {@code transaccion}.
     *       Con FOR UPDATE, un INSERT concurrente que referencia la cuenta queda bloqueado y
     *       se producen deadlocks (detectado en ConsistenciaTest).</li>
     * </ul>
     */
    @Query(value = "SELECT * FROM cuenta WHERE numero IN (:numeros) ORDER BY id FOR NO KEY UPDATE",
            nativeQuery = true)
    List<Cuenta> bloquearPorNumeros(@Param("numeros") Collection<String> numeros);

    /*
     * Alternativa JPA estándar (portable entre motores, pero genera FOR UPDATE):
     *   @Lock(LockModeType.PESSIMISTIC_WRITE)
     *   @Query("select c from Cuenta c where c.numero in :numeros order by c.id")
     */
}
