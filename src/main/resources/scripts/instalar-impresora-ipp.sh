#!/bin/bash
# ====================================================================
# Script de Instalación de Impresora IPP para Linux
# Version 1.0 - IPP con soporte PDF directo
# ====================================================================

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuración del servidor
# IMPORTANTE: Usar IP para conexión directa
SERVER_HOST="10.1.16.31"
SERVER_PORT="8080"

# Verificar si se ejecuta como root
if [ "$EUID" -eq 0 ]; then 
    echo -e "${RED}ERROR: No ejecutes este script como root/sudo${NC}"
    echo "El script solicitará permisos cuando sea necesario"
    exit 1
fi

clear
echo ""
echo "===================================================================="
echo "  INSTALADOR DE IMPRESORA IPP PARA LINUX"
echo "===================================================================="
echo ""
echo "  Servidor: $SERVER_IP:$SERVER_PORT"
echo ""
echo "===================================================================="
echo ""

# Verificar que CUPS esté instalado
echo -e "${YELLOW}Verificando CUPS...${NC}"
if ! command -v lpstat &> /dev/null; then
    echo -e "${RED}ERROR: CUPS no está instalado${NC}"
    echo ""
    echo "Instala CUPS con:"
    echo "  Ubuntu/Debian: sudo apt install cups"
    echo "  Fedora/RHEL:   sudo dnf install cups"
    echo "  Arch:          sudo pacman -S cups"
    exit 1
fi
echo -e "${GREEN}✓ CUPS instalado${NC}"
echo ""

# Verificar que el servicio CUPS esté corriendo
echo -e "${YELLOW}Verificando servicio CUPS...${NC}"
if ! systemctl is-active --quiet cups; then
    echo -e "${YELLOW}Iniciando servicio CUPS...${NC}"
    sudo systemctl start cups
    sleep 2
fi

if systemctl is-active --quiet cups; then
    echo -e "${GREEN}✓ Servicio CUPS activo${NC}"
else
    echo -e "${RED}ERROR: No se pudo iniciar CUPS${NC}"
    exit 1
fi
echo ""

# Obtener lista de impresoras del servidor
echo -e "${YELLOW}Obteniendo lista de impresoras...${NC}"
echo ""

TEMP_JSON="/tmp/printers_$$.json"

# Intentar con curl primero, luego wget
if command -v curl &> /dev/null; then
    curl -s "http://$SERVER_IP:$SERVER_PORT/api/printers" -o "$TEMP_JSON" 2>/dev/null
elif command -v wget &> /dev/null; then
    wget -q "http://$SERVER_IP:$SERVER_PORT/api/printers" -O "$TEMP_JSON" 2>/dev/null
else
    echo -e "${RED}ERROR: No se encontró curl ni wget${NC}"
    echo "Instala uno de ellos para continuar"
    exit 1
fi

# Verificar que se obtuvo la lista
if [ ! -s "$TEMP_JSON" ]; then
    echo -e "${RED}ERROR: No se pudo conectar al servidor${NC}"
    echo ""
    echo "POSIBLES CAUSAS:"
    echo "  1. El servidor no está accesible en $SERVER_IP:$SERVER_PORT"
    echo "  2. Firewall bloqueando la conexión"
    echo "  3. Servidor de impresoras no está ejecutándose"
    rm -f "$TEMP_JSON"
    exit 1
fi

# Verificar que jq esté instalado para parsear JSON
if ! command -v jq &> /dev/null; then
    echo -e "${YELLOW}Instalando jq para parsear JSON...${NC}"
    if command -v apt-get &> /dev/null; then
        sudo apt-get update -qq && sudo apt-get install -y jq -qq
    elif command -v dnf &> /dev/null; then
        sudo dnf install -y jq -q
    elif command -v pacman &> /dev/null; then
        sudo pacman -S --noconfirm jq
    else
        echo -e "${RED}ERROR: No se pudo instalar jq automáticamente${NC}"
        echo "Por favor instala jq manualmente"
        rm -f "$TEMP_JSON"
        exit 1
    fi
fi

# Parsear y mostrar impresoras
echo "===================================================================="
echo "  IMPRESORAS DISPONIBLES EN EL SERVIDOR"
echo "===================================================================="
echo ""

PRINTER_COUNT=$(jq 'length' "$TEMP_JSON")

if [ "$PRINTER_COUNT" -eq 0 ]; then
    echo -e "${RED}No hay impresoras disponibles en el servidor${NC}"
    rm -f "$TEMP_JSON"
    exit 1
fi

# Mostrar lista de impresoras
for i in $(seq 0 $(($PRINTER_COUNT - 1))); do
    NUM=$(($i + 1))
    ALIAS=$(jq -r ".[$i].alias" "$TEMP_JSON")
    MODEL=$(jq -r ".[$i].model" "$TEMP_JSON")
    IP=$(jq -r ".[$i].ip" "$TEMP_JSON")
    PORT=$(jq -r ".[$i].ippPort" "$TEMP_JSON")
    LOCATION=$(jq -r ".[$i].location" "$TEMP_JSON")
    
    echo -e "${CYAN}[$NUM]${NC} $ALIAS"
    echo "    Modelo: $MODEL"
    echo "    Ubicación: $LOCATION"
    echo "    Servidor: $IP:$PORT"
    echo ""
done

# Seleccionar impresora
echo -n "Selecciona el número de la impresora (1-$PRINTER_COUNT): "
read SELECTION

# Validar selección
if ! [[ "$SELECTION" =~ ^[0-9]+$ ]] || [ "$SELECTION" -lt 1 ] || [ "$SELECTION" -gt "$PRINTER_COUNT" ]; then
    echo -e "${RED}ERROR: Selección inválida${NC}"
    rm -f "$TEMP_JSON"
    exit 1
fi

# Obtener datos de la impresora seleccionada
IDX=$(($SELECTION - 1))
PRINTER_NAME=$(jq -r ".[$IDX].alias" "$TEMP_JSON")
PRINTER_MODEL=$(jq -r ".[$IDX].model" "$TEMP_JSON")
PRINTER_IP="$SERVER_IP"
PRINTER_PORT=$(jq -r ".[$IDX].ippPort" "$TEMP_JSON")
PRINTER_LOCATION=$(jq -r ".[$IDX].location" "$TEMP_JSON")

# Verificar si es impresora USB compartida
IS_SHARED_USB=false
if echo "$PRINTER_LOCATION" | grep -q "Compartida-USB"; then
    IS_SHARED_USB=true
fi

echo ""
echo "===================================================================="
echo "  IMPRESORA SELECCIONADA"
echo "===================================================================="
echo ""
echo "  Nombre: $PRINTER_NAME"
echo "  Modelo: $PRINTER_MODEL"
echo "  Servidor: $PRINTER_IP:$PRINTER_PORT"
if [ "$IS_SHARED_USB" = true ]; then
    echo "  Tipo: USB Compartida (requiere cliente encendido)"
fi
echo ""

# Construir URI IPP
IPP_URI="ipp://$PRINTER_IP:$PRINTER_PORT/printers/${PRINTER_NAME// /_}"

echo -e "${YELLOW}Verificando conectividad con el servidor...${NC}"

# Verificar que el puerto IPP esté accesible
if timeout 5 bash -c "cat < /dev/null > /dev/tcp/$PRINTER_IP/$PRINTER_PORT" 2>/dev/null; then
    echo -e "${GREEN}✓ Servidor IPP accesible en puerto $PRINTER_PORT${NC}"
else
    echo -e "${RED}ERROR: No se puede conectar al puerto IPP $PRINTER_PORT${NC}"
    echo ""
    echo "POSIBLES CAUSAS:"
    echo "  1. Firewall bloqueando el puerto"
    echo "  2. Servidor no está ejecutándose"
    if [ "$IS_SHARED_USB" = true ]; then
        echo "  3. Computadora con USB compartida está APAGADA"
    fi
    rm -f "$TEMP_JSON"
    exit 1
fi
echo ""

# Limpiar instalaciones previas
echo -e "${YELLOW}Limpiando instalaciones previas...${NC}"

# Nombre seguro para CUPS (sin espacios ni caracteres especiales)
CUPS_NAME="${PRINTER_NAME// /_}"
CUPS_NAME="${CUPS_NAME//[^a-zA-Z0-9_-]/}"

# Eliminar impresora si existe
if lpstat -p "$CUPS_NAME" &> /dev/null; then
    echo "  Eliminando impresora existente: $CUPS_NAME"
    sudo lpadmin -x "$CUPS_NAME" 2>/dev/null
    sleep 1
fi
echo ""

# Instalar impresora
echo -e "${YELLOW}Instalando impresora...${NC}"
echo "  Nombre: $CUPS_NAME"
echo "  URI: $IPP_URI"
echo ""

# Usar driver everywhere para soporte PDF directo
# IPP Everywhere es el estándar moderno que soporta PDF, PWG Raster, etc.
echo -e "${CYAN}Usando IPP Everywhere (soporte PDF directo)${NC}"

# Agregar impresora con lpadmin
# -p: nombre de la impresora
# -v: URI del dispositivo
# -m: modelo/driver (everywhere = IPP Everywhere, soporta PDF)
# -E: habilitar la impresora y aceptar trabajos
# -L: ubicación
# -D: descripción

sudo lpadmin -p "$CUPS_NAME" \
    -v "$IPP_URI" \
    -m everywhere \
    -E \
    -L "$PRINTER_LOCATION" \
    -D "$PRINTER_NAME - $PRINTER_MODEL" \
    -o printer-is-shared=false \
    2>&1 | tee /tmp/lpadmin_error.log

if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✓ Impresora instalada correctamente${NC}"
else
    echo ""
    echo -e "${YELLOW}⚠ Driver 'everywhere' no disponible, intentando con raw...${NC}"
    
    # Fallback: usar raw (envía datos sin modificar)
    sudo lpadmin -p "$CUPS_NAME" \
        -v "$IPP_URI" \
        -m raw \
        -E \
        -L "$PRINTER_LOCATION" \
        -D "$PRINTER_NAME - $PRINTER_MODEL" \
        -o printer-is-shared=false
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Impresora instalada con driver RAW${NC}"
        echo -e "${YELLOW}  Nota: Para mejor calidad, instala ipp-usb o drivers del fabricante${NC}"
    else
        echo -e "${RED}ERROR: No se pudo instalar la impresora${NC}"
        cat /tmp/lpadmin_error.log
        rm -f "$TEMP_JSON" /tmp/lpadmin_error.log
        exit 1
    fi
fi

# Habilitar la impresora
sudo cupsenable "$CUPS_NAME" 2>/dev/null
sudo cupsaccept "$CUPS_NAME" 2>/dev/null

echo ""

# Configurar como predeterminada (opcional)
echo -n "¿Establecer como impresora predeterminada? (s/N): "
read SET_DEFAULT

if [ "$SET_DEFAULT" = "s" ] || [ "$SET_DEFAULT" = "S" ]; then
    sudo lpadmin -d "$CUPS_NAME"
    echo -e "${GREEN}✓ Impresora establecida como predeterminada${NC}"
fi

echo ""
echo "===================================================================="
echo -e "  ${GREEN}INSTALACIÓN COMPLETADA${NC}"
echo "===================================================================="
echo ""
echo "  Nombre: $CUPS_NAME"
echo "  URI: $IPP_URI"
echo "  Puerto: $PRINTER_PORT (FIJO)"
echo ""
echo "  DRIVER INSTALADO: IPP Everywhere"
echo "  - Soporta PDF directo (mantiene formato)"
echo "  - Compatible con documentos de LibreOffice, PDFs, etc."
echo "  - Conserva colores, negritas, tablas, imágenes"
echo ""
echo "===================================================================="
echo ""
echo "COMANDOS ÚTILES:"
echo ""
echo "  Ver estado:       lpstat -p $CUPS_NAME"
echo "  Ver trabajos:     lpstat -o"
echo "  Imprimir archivo: lp -d $CUPS_NAME archivo.pdf"
echo "  Eliminar:         sudo lpadmin -x $CUPS_NAME"
echo ""
echo "===================================================================="
echo ""
echo "Puedes imprimir desde cualquier aplicación."
echo ""

# Limpiar archivos temporales
rm -f "$TEMP_JSON" /tmp/lpadmin_error.log

exit 0


exit 0
