package com.example.base_datos.web;

import com.example.base_datos.domain.TransaccionFallida;
import com.example.base_datos.service.*;
import com.example.base_datos.web.dto.Peticiones.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api")
@Validated
public class BancoController {

    private final TransferenciaService transferencias;
    private final LoteService lotes;
    private final CuentaService cuentas;
    private final ReporteService reportes;

    public BancoController(TransferenciaService transferencias, LoteService lotes,
                           CuentaService cuentas, ReporteService reportes) {
        this.transferencias = transferencias;
        this.lotes = lotes;
        this.cuentas = cuentas;
        this.reportes = reportes;
    }

    // ---------------------------------------------------------------- cuentas
    @PostMapping("/cuentas")
    @ResponseStatus(HttpStatus.CREATED)
    public CuentaService.CuentaVista abrirCuenta(@Valid @RequestBody AbrirCuentaRequest r) {
        return cuentas.abrirCuenta(r.documento(), r.nombre(), r.email(), r.numero());
    }

    @GetMapping("/cuentas/{numero}")
    public CuentaService.CuentaVista consultar(@PathVariable String numero) {
        return cuentas.consultar(numero);
    }

    @GetMapping("/cuentas/{numero}/extracto")
    public List<ProcedimientosDao.MovimientoExtracto> extracto(@PathVariable String numero,
                                                               @RequestParam(defaultValue = "50") int limite) {
        return cuentas.extracto(numero, limite);
    }

    @PostMapping("/cuentas/{numero}/cierre")
    public CuentaService.CuentaVista cerrar(@PathVariable String numero, @Valid @RequestBody CierreRequest r) {
        return cuentas.cerrarCuenta(numero, r.cuentaDestino());
    }

    // ---------------------------------------------------------------- movimientos
    @PostMapping("/depositos")
    @ResponseStatus(HttpStatus.CREATED)
    public Comprobante depositar(@Valid @RequestBody DepositoRequest r) {
        return transferencias.depositar(r.numero(), r.monto(), r.referencia());
    }

    @PostMapping("/transferencias")
    @ResponseStatus(HttpStatus.CREATED)
    public Comprobante transferir(@Valid @RequestBody TransferenciaRequest r) {
        SolicitudTransferencia s = r.aSolicitud();
        return switch (r.estrategiaOPorDefecto()) {
            case PESIMISTA -> transferencias.transferir(s);
            case OPTIMISTA -> transferencias.transferirOptimista(s);
            case PROCEDIMIENTO -> transferencias.transferirConProcedimiento(s);
        };
    }

    @PostMapping("/lotes")
    public LoteService.ResultadoLote lote(@Valid @RequestBody LoteRequest r) {
        return lotes.procesar(r.transferencias().stream().map(TransferenciaRequest::aSolicitud).toList(), r.modo());
    }

    // ---------------------------------------------------------------- reportes / procesos
    @GetMapping("/reportes/resumen")
    public ReporteService.ResumenSistema resumen() {
        return reportes.resumen();
    }

    @GetMapping("/transacciones/fallidas")
    public List<TransaccionFallida> fallidas(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limite) {
        return reportes.fallidasRecientes(limite);
    }

    @PostMapping("/procesos/intereses")
    public ProcedimientosDao.ResultadoIntereses intereses(
            @RequestParam @DecimalMin(value = "0", inclusive = false) @DecimalMax("0.10") BigDecimal tasa,
            @RequestParam(defaultValue = "1000") @Min(1) @Max(100000) int lote) {
        return reportes.aplicarIntereses(tasa, lote);
    }
}
