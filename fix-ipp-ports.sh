#!/bin/bash
# Script para verificar y corregir puertos IPP en la base de datos

echo "════════════════════════════════════════════════════════════"
echo "🔧 Verificación y Corrección de Puertos IPP"
echo "════════════════════════════════════════════════════════════"
echo ""

# Configuración de PostgreSQL
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="impre"
DB_USER="postgres"
export PGPASSWORD="1212"

echo "📋 Estado actual de las impresoras:"
echo "════════════════════════════════════════════════════════════"
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
SELECT 
    id, 
    alias, 
    ip, 
    ipp_port as puerto_ipp,
    CASE 
        WHEN ipp_port IS NULL THEN '❌ SIN ASIGNAR'
        ELSE '✅ ASIGNADO'
    END as estado
FROM printer 
ORDER BY id;
"

echo ""
echo "════════════════════════════════════════════════════════════"
echo "🔍 Impresoras sin puerto IPP asignado:"
echo "════════════════════════════════════════════════════════════"
COUNT=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "
SELECT COUNT(*) FROM printer WHERE ipp_port IS NULL;
")

if [ "$COUNT" -gt 0 ]; then
    echo "⚠️  Encontradas $COUNT impresoras sin puerto IPP"
    echo ""
    psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
    SELECT id, alias FROM printer WHERE ipp_port IS NULL ORDER BY id;
    "
    
    echo ""
    read -p "¿Desea asignar puertos automáticamente? (s/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Ss]$ ]]; then
        echo "🔄 Asignando puertos IPP..."
        
        # Asignar puerto = 8630 + ID de la impresora
        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
        UPDATE printer 
        SET ipp_port = 8630 + id 
        WHERE ipp_port IS NULL;
        "
        
        echo "✅ Puertos asignados"
    else
        echo "❌ Operación cancelada"
        exit 0
    fi
else
    echo "✅ Todas las impresoras tienen puerto IPP asignado"
fi

echo ""
echo "════════════════════════════════════════════════════════════"
echo "📊 Mapeo final de puertos:"
echo "════════════════════════════════════════════════════════════"
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
SELECT 
    ipp_port as puerto_servidor,
    alias as impresora,
    ip as ip_fisica,
    port as puerto_fisico,
    protocol,
    CONCAT('ipp://10.1.16.31:', ipp_port, '/printers/', alias) as uri_completo
FROM printer 
ORDER BY ipp_port;
"

echo ""
echo "════════════════════════════════════════════════════════════"
echo "⚠️  IMPORTANTE: Debes REINICIAR el servidor Java para que los"
echo "   cambios surtan efecto:"
echo ""
echo "   sudo systemctl restart print-manager"
echo "   # O si lo ejecutas manualmente:"
echo "   pkill -f java"
echo "   cd ~/Servidori && mvn spring-boot:run"
echo "════════════════════════════════════════════════════════════"

# Limpiar variable de entorno
unset PGPASSWORD
