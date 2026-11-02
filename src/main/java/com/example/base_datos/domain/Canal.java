package com.example.base_datos.domain;

/** Por dónde se ejecutó la transacción: lógica en el framework (JPA), procedimiento almacenado o proceso por lotes. */
public enum Canal {
    JPA, PROCEDURE, LOTE
}
