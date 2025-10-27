#!/bin/bash
# Script simple para ver qué impresora está en qué puerto

echo "🖨️  Mapeo de Puertos IPP → Impresoras"
echo "════════════════════════════════════════════════════════════"

export PGPASSWORD="1212"

psql -h localhost -p 5432 -U postgres -d impre -c "
SELECT 
    CONCAT('Puerto ', COALESCE(ipp_port::text, 'NO ASIGNADO')) as puerto,
    alias as impresora,
    ip as destino
FROM printer 
ORDER BY COALESCE(ipp_port, 99999), id;
" -P pager=off

echo ""
echo "📡 Puertos actualmente escuchando en el servidor:"
netstat -tlnp 2>/dev/null | grep java | grep 863 || echo "   (no se encontraron puertos 863x abiertos)"

unset PGPASSWORD
