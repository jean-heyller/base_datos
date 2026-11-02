-- =====================================================================
-- V4: Datos de demostración (toda la migración corre en UNA transacción:
-- Flyway hace COMMIT al final o ROLLBACK completo si algo falla).
-- =====================================================================
INSERT INTO cliente (documento, nombre, email) VALUES
    ('1001', 'Ana Gómez',     'ana@demo.co'),
    ('1002', 'Bruno Díaz',    'bruno@demo.co'),
    ('1003', 'Carla Méndez',  'carla@demo.co');

INSERT INTO cuenta (numero, cliente_id, saldo, estado) VALUES
    ('ACC-001', (SELECT id FROM cliente WHERE documento = '1001'), 1000000.00, 'ACTIVA'),
    ('ACC-002', (SELECT id FROM cliente WHERE documento = '1002'),  500000.00, 'ACTIVA'),
    ('ACC-003', (SELECT id FROM cliente WHERE documento = '1003'),  250000.00, 'ACTIVA'),
    ('ACC-004', (SELECT id FROM cliente WHERE documento = '1003'),   10000.00, 'BLOQUEADA');
