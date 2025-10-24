-- Script para agregar columnas faltantes a la tabla printer
-- Ejecutar esto en PostgreSQL (base de datos: impre)

-- Agregar columnas nuevas a la tabla printer
ALTER TABLE printer ADD COLUMN IF NOT EXISTS device_uri VARCHAR(500);
ALTER TABLE printer ADD COLUMN IF NOT EXISTS driver VARCHAR(255);
ALTER TABLE printer ADD COLUMN IF NOT EXISTS port INTEGER;
ALTER TABLE printer ADD COLUMN IF NOT EXISTS protocol VARCHAR(50);
ALTER TABLE printer ADD COLUMN IF NOT EXISTS shared_via_samba BOOLEAN DEFAULT FALSE;
ALTER TABLE printer ADD COLUMN IF NOT EXISTS added_to_cups BOOLEAN DEFAULT FALSE;
ALTER TABLE printer ADD COLUMN IF NOT EXISTS samba_share_name VARCHAR(255);

-- Verificar que se agregaron
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name = 'printer' 
ORDER BY ordinal_position;
