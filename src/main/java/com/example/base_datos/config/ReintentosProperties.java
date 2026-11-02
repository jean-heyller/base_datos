package com.example.base_datos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Política de reintentos ante conflictos de concurrencia (bloqueo optimista, deadlock, serialización).
 * Backoff exponencial con jitter y con TOPE: sin tope, 5 ms * 2^30 son ~2 meses.
 */
@ConfigurationProperties(prefix = "banco.reintentos")
public record ReintentosProperties(Integer maximos, Long esperaInicialMs, Long esperaMaximaMs) {

    public ReintentosProperties {
        if (maximos == null || maximos < 1) {
            maximos = 5;
        }
        if (esperaInicialMs == null || esperaInicialMs < 0) {
            esperaInicialMs = 20L;
        }
        if (esperaMaximaMs == null || esperaMaximaMs < esperaInicialMs) {
            esperaMaximaMs = 500L;
        }
    }
}
