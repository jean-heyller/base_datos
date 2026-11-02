package com.example.base_datos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaseDatosApplicationTests extends BaseIntegracionTest {

    @Test
    void contextLoadsYMigracionesAplicadas() {
        Integer migraciones = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(migraciones).isEqualTo(4);
        assertThat(saldo("ACC-001")).isNotNull();
    }
}
