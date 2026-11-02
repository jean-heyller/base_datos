package com.example.base_datos.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Control de transacciones (TCL) en el framework.
 * <ul>
 *   <li>Declarativo: {@code @Transactional} => Spring emite BEGIN / COMMIT / ROLLBACK.</li>
 *   <li>Programático: {@code TransactionTemplate} con aislamiento/timeout explícitos, y SAVEPOINT /
 *       ROLLBACK TO SAVEPOINT / RELEASE SAVEPOINT en {@code LoteService}.</li>
 *   <li>Propagación: REQUIRED (por defecto), REQUIRES_NEW ({@code RegistroFallosService}),
 *       NOT_SUPPORTED (procedimiento con COMMIT interno).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableConfigurationProperties(ReintentosProperties.class)
public class TransaccionConfig {

    @Bean
    public JpaTransactionManager transactionManager(EntityManagerFactory emf) {
        JpaTransactionManager tm = new JpaTransactionManager(emf);
        // Ninguna transacción puede retener locks indefinidamente.
        tm.setDefaultTimeout(30);
        // Si un participante interno marcó rollback-only, el COMMIT externo falla de forma explícita.
        tm.setFailEarlyOnGlobalRollbackOnly(true);
        return tm;
    }
}
