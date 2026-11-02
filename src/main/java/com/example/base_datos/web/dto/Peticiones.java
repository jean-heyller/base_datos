package com.example.base_datos.web.dto;

import com.example.base_datos.service.LoteService;
import com.example.base_datos.service.SolicitudTransferencia;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Cuerpos de petición de la API. La validación aquí es la primera barrera; el motor es la última. */
public final class Peticiones {

    private Peticiones() {
    }

    public enum Estrategia { PESIMISTA, OPTIMISTA, PROCEDIMIENTO }

    public record TransferenciaRequest(
            @NotBlank @Size(max = 20) String origen,
            @NotBlank @Size(max = 20) String destino,
            @NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal monto,
            UUID referencia,
            @Size(max = 200) String descripcion,
            Estrategia estrategia) {

        public SolicitudTransferencia aSolicitud() {
            return new SolicitudTransferencia(origen, destino, monto, referencia, descripcion);
        }

        public Estrategia estrategiaOPorDefecto() {
            return estrategia == null ? Estrategia.PESIMISTA : estrategia;
        }
    }

    public record DepositoRequest(
            @NotBlank @Size(max = 20) String numero,
            @NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal monto,
            UUID referencia) {
    }

    public record LoteRequest(
            @NotNull LoteService.Modo modo,
            @NotEmpty @Size(max = 1000) List<@Valid TransferenciaRequest> transferencias) {
    }

    public record AbrirCuentaRequest(
            @NotBlank @Size(max = 20) String documento,
            @NotBlank @Size(max = 120) String nombre,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(max = 20) String numero) {
    }

    public record CierreRequest(@NotBlank @Size(max = 20) String cuentaDestino) {
    }
}
