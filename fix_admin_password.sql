-- Script para resetear la contraseña del usuario admin
-- Ejecuta esto en pgAdmin antes de reiniciar la aplicación

-- 1. Opción A: Borrar el usuario admin para que se recree automáticamente
DELETE FROM user_table WHERE username = 'admin';

-- 2. Resetear la secuencia (importante)
SELECT setval('gen', 0, false);

-- Después de ejecutar este script, reinicia la aplicación y el usuario admin 
-- se creará automáticamente con la contraseña correcta.

-- NOTA: Si quieres actualizar la contraseña manualmente sin borrar el usuario,
-- comenta las líneas anteriores y usa esta en su lugar (ejecuta primero la app para obtener el hash):
-- UPDATE user_table SET password = '{bcrypt}$2a$10$TU_NUEVO_HASH_AQUI' WHERE username = 'admin';
