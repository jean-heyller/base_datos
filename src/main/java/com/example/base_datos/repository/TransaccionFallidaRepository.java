package com.example.base_datos.repository;

import com.example.base_datos.domain.TransaccionFallida;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TransaccionFallidaRepository extends JpaRepository<TransaccionFallida, Long> {

    List<TransaccionFallida> findByReferencia(UUID referencia);

    @Query("select f from TransaccionFallida f order by f.creadoEn desc, f.id desc")
    List<TransaccionFallida> recientes(Pageable pageable);
}
