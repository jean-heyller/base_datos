package com.example.base_datos.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Perfil "embedded": levanta un PostgreSQL real (binarios oficiales) dentro del proceso.
 * Permite ejecutar la aplicación y los tests sin instalar PostgreSQL ni Docker.
 * Es el MISMO motor que en producción: procedimientos PL/pgSQL, FOR UPDATE, SERIALIZABLE, etc.
 */
@Configuration(proxyBeanMethods = false)
@Profile("embedded")
public class EmbeddedPostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres() throws IOException {
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                // zonky desactiva fsync por velocidad; lo reactivamos: DURABILIDAD real (WAL a disco en cada COMMIT)
                .setServerConfig("fsync", "on")
                .setServerConfig("synchronous_commit", "on")
                .setServerConfig("full_page_writes", "on")
                .setServerConfig("max_connections", "100")
                .start();
        log.info("PostgreSQL embebido escuchando en {}", pg.getJdbcUrl("postgres", "postgres"));
        return pg;
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(EmbeddedPostgres pg) {
        HikariConfig cfg = new HikariConfig();
        // lock_timeout como parámetro de arranque de la sesión (no con connectionInitSql: con autocommit=false
        // ese SET deja la conexión "idle in transaction" y PostgreSQL lo descarta en el primer ROLLBACK).
        cfg.setJdbcUrl(pg.getJdbcUrl("postgres", "postgres") + "&options=-c%20lock_timeout%3D5s");
        cfg.setUsername("postgres");
        cfg.setPassword("postgres");
        cfg.setMaximumPoolSize(20);
        cfg.setAutoCommit(false);
        cfg.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        cfg.setPoolName("banco-embedded");
        return new HikariDataSource(cfg);
    }

    /** Expone la URL para que los tests puedan abrir conexiones JDBC totalmente independientes del pool. */
    public static String jdbcUrl(DataSource ds) {
        return ((HikariDataSource) ds).getJdbcUrl();
    }
}
