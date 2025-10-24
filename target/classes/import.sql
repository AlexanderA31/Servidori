-- Sistema de Gestión de Impresoras - Datos Iniciales
-- Solo se inserta el usuario administrador por defecto

-- Usuario Admin (username: admin, password: admin123)
INSERT INTO user_table (id, enabled, roles, username, password)
VALUES (1, TRUE, 'ADMIN,USER', 'admin', '{bcrypt}$2a$10$mF3RZ/qG.9lV1wBXQKHvguLBKpZWZWMTOxH.2E2xqJbqQbqPpNNNu')
ON CONFLICT DO NOTHING;

-- Actualizar secuencia para evitar conflictos de ID
SELECT setval('gen', (SELECT COALESCE(MAX(id), 1) FROM user_table), true);

-- No se insertan datos de ejemplo de impresoras, grupos ni trabajos
-- El administrador los creará desde el panel de administración
