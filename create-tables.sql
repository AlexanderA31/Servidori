-- Script SQL para crear tablas faltantes en PostgreSQL
-- Ejecutar: psql -U postgres -d impre -f create-tables.sql

-- Tabla NetworkRange (para gestión de VLANs)
CREATE TABLE IF NOT EXISTS network_range (
    id BIGINT PRIMARY KEY,
    instance_id BIGINT,
    name VARCHAR(255) NOT NULL,
    cidr_range VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    vlan_id INTEGER,
    active BOOLEAN DEFAULT TRUE,
    last_scan TIMESTAMP,
    last_found_printers INTEGER DEFAULT 0,
    FOREIGN KEY (instance_id) REFERENCES user_table(id) ON DELETE CASCADE
);

-- Índices para mejor rendimiento
CREATE INDEX IF NOT EXISTS idx_network_range_active ON network_range(active);
CREATE INDEX IF NOT EXISTS idx_network_range_instance ON network_range(instance_id);

-- Añadir nuevas columnas a Printer si no existen
DO $$ 
BEGIN
    -- device_uri
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='printer' AND column_name='device_uri'
    ) THEN
        ALTER TABLE printer ADD COLUMN device_uri VARCHAR(500);
    END IF;
    
    -- driver
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='printer' AND column_name='driver'
    ) THEN
        ALTER TABLE printer ADD COLUMN driver VARCHAR(255);
    END IF;
    
    -- port
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='printer' AND column_name='port'
    ) THEN
        ALTER TABLE printer ADD COLUMN port INTEGER;
    END IF;
    
    -- protocol
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='printer' AND column_name='protocol'
    ) THEN
        ALTER TABLE printer ADD COLUMN protocol VARCHAR(50);
    END IF;
    
    -- shared_via_samba
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='printer' AND column_name='shared_via_samba'
    ) THEN
        ALTER TABLE printer ADD COLUMN shared_via_samba BOOLEAN DEFAULT FALSE;
    END IF;
    
    -- added_to_cups
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='printer' AND column_name='added_to_cups'
    ) THEN
        ALTER TABLE printer ADD COLUMN added_to_cups BOOLEAN DEFAULT FALSE;
    END IF;
    
    -- samba_share_name
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='printer' AND column_name='samba_share_name'
    ) THEN
        ALTER TABLE printer ADD COLUMN samba_share_name VARCHAR(255);
    END IF;
END $$;

-- Crear rangos de red por defecto si no existen
INSERT INTO network_range (id, instance_id, name, cidr_range, description, active, last_found_printers)
SELECT 
    nextval('gen'),
    1,
    'Red Local Principal',
    '192.168.1.0/24',
    'Red local por defecto',
    TRUE,
    0
WHERE NOT EXISTS (
    SELECT 1 FROM network_range WHERE cidr_range = '192.168.1.0/24'
);

-- Mostrar resumen
SELECT 'Tablas creadas exitosamente' AS mensaje;
SELECT table_name FROM information_schema.tables 
WHERE table_schema = 'public' 
ORDER BY table_name;
