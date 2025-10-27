-- Script para verificar y asignar puertos IPP fijos a las impresoras
-- Ejecutar en PostgreSQL: psql -U postgres -d impre -f fix-ipp-ports.sql

-- 1. Ver estado actual de las impresoras (ordenadas por ID)
SELECT 
    id, 
    alias, 
    ip, 
    port as puerto_fisico,
    ipp_port as puerto_ipp_servidor,
    protocol
FROM printer 
ORDER BY id;

-- 2. Ver cuáles impresoras NO tienen puerto IPP asignado
SELECT 
    id, 
    alias, 
    'Puerto IPP no asignado' as estado
FROM printer 
WHERE ipp_port IS NULL
ORDER BY id;

-- 3. Asignar puertos IPP secuencialmente basados en el ID
-- IMPORTANTE: Esto asigna puertos fijos comenzando desde 8631
-- Solo ejecutar si quieres REASIGNAR todos los puertos

-- OPCIÓN A: Asignar solo a las que NO tienen puerto
UPDATE printer 
SET ipp_port = 8630 + id 
WHERE ipp_port IS NULL;

-- OPCIÓN B (COMENTADA): Reasignar TODOS los puertos basándose en ID
-- USE ESTO SOLO SI QUIERE COMENZAR DE CERO
-- UPDATE printer 
-- SET ipp_port = 8630 + id;

-- 4. Verificar resultado final
SELECT 
    id, 
    alias, 
    ip as ip_fisica,
    port as puerto_fisico,
    ipp_port as puerto_ipp,
    CONCAT('ipp://10.1.16.31:', ipp_port, '/printers/', alias) as uri_ipp
FROM printer 
ORDER BY id;

-- 5. Ver mapeo de puertos
SELECT 
    ipp_port as puerto_servidor,
    alias as impresora,
    ip as destino_fisico
FROM printer 
ORDER BY ipp_port;
