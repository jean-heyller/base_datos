package com.example.base_datos.service;

import com.example.base_datos.domain.TipoTransaccion;
import com.example.base_datos.domain.TransaccionFallida;
import com.example.base_datos.repository.TransaccionFallidaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Registra intentos fallidos en una transacción INDEPENDIENTE.
 * <p>
 * {@code REQUIRES_NEW} suspende la transacción de negocio (que va a hacer ROLLBACK), abre otra
 * conexión, inserta y hace COMMIT propio. Así el registro del fallo es durable aunque la
 * operación principal se revierta por completo (equivalente a una "transacción autónoma").
 * Debe estar en otro bean: las llamadas internas (this.metodo()) no pasan por el proxy de Spring.
 */
@Service
public class RegistroFallosService {

    private final TransaccionFallidaRepository repo;

    public RegistroFallosService(TransaccionFallidaRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void registrar(UUID referencia, TipoTransaccion tipo, String origen, String destino,
                          BigDecimal monto, String motivo) {
        repo.save(new TransaccionFallida(referencia, tipo, origen, destino, monto, motivo));
    }

    /** También REQUIRES_NEW: llamar a this.registrar(...) no pasaría por el proxy transaccional. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void registrar(SolicitudTransferencia s, RuntimeException causa) {
        repo.save(new TransaccionFallida(s.referencia(), TipoTransaccion.TRANSFERENCIA, s.origen(), s.destino(),
                s.monto(), String.valueOf(causa.getMessage())));
    }
}
