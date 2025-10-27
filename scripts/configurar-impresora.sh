x#!/bin/bash
# ===================================================================
# Script Universal para Configurar Impresoras IPP desde Servidor CUPS
# ===================================================================
# Uso: ./configurar-impresora.sh <printer-name> [alias] [server-ip] [port]
# Ejemplo: ./configurar-impresora.sh EPSON26 "Epson Oficina" 10.1.1.43 8631

set -e

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Banner
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  Instalador de Impresora IPP para Linux${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo ""

# Validar parámetros
if [ $# -lt 1 ]; then
    echo -e "${RED}❌ ERROR: Faltan parámetros${NC}"
    echo ""
    echo "Uso: $0 <printer-name> [alias] [server-ip] [port]"
    echo ""
    echo "Ejemplos:"
    echo "  $0 EPSON26"
    echo "  $0 EPSON26 'Epson Oficina'"
    echo "  $0 HP_LaserJet 'HP' 10.1.1.43 8631"
    echo ""
    exit 1
fi

PRINTER_NAME="$1"
PRINTER_ALIAS="${2:-$PRINTER_NAME}"
SERVER_IP="${3:-10.1.1.79}"
PORT="${4:-8631}"

# Construir URI
PRINTER_URI="ipp://${SERVER_IP}:${PORT}/printers/${PRINTER_NAME}"

echo -e "${GREEN}Configuración:${NC}"
echo -e "  Servidor: ${SERVER_IP}"
echo -e "  Puerto: ${PORT}"
echo -e "  Impresora: ${PRINTER_NAME}"
echo -e "  Alias: ${PRINTER_ALIAS}"
echo -e "  URI: ${PRINTER_URI}"
echo ""

# Verificar si CUPS está instalado
if ! command -v lpadmin &> /dev/null; then
    echo -e "${YELLOW}⚠ CUPS no está instalado${NC}"
    echo ""
    echo "Instalando CUPS..."
    
    if [ -f /etc/debian_version ]; then
        sudo apt-get update
        sudo apt-get install -y cups
    elif [ -f /etc/redhat-release ]; then
        sudo yum install -y cups
    else
        echo -e "${RED}❌ ERROR: Sistema operativo no soportado${NC}"
        echo "Instala CUPS manualmente: https://www.cups.org/"
        exit 1
    fi
fi

# Verificar conectividad
echo -e "${YELLOW}⏳ Verificando conectividad...${NC}"

if timeout 3 bash -c "cat < /dev/null > /dev/tcp/${SERVER_IP}/${PORT}" 2>/dev/null; then
    echo -e "${GREEN}✓ Servidor accesible${NC}"
else
    echo -e "${RED}❌ ERROR: No se puede conectar al servidor en el puerto ${PORT}${NC}"
    echo ""
    echo -e "${YELLOW}Verifica que:${NC}"
    echo "  1. El servidor esté encendido"
    echo "  2. El firewall permita conexiones al puerto ${PORT}"
    echo "  3. La IP del servidor sea correcta: ${SERVER_IP}"
    exit 1
fi

echo ""

# Agregar impresora
echo -e "${YELLOW}⏳ Agregando impresora a CUPS...${NC}"

# Eliminar si ya existe
if lpstat -p "${PRINTER_NAME}" &>/dev/null; then
    echo -e "${YELLOW}⚠ La impresora ya existe, eliminando...${NC}"
    sudo lpadmin -x "${PRINTER_NAME}"
fi

# Agregar impresora IPP
sudo lpadmin -p "${PRINTER_NAME}" \
    -E \
    -v "${PRINTER_URI}" \
    -D "${PRINTER_ALIAS}" \
    -L "Servidor: ${SERVER_IP}" \
    -m everywhere

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Impresora agregada exitosamente${NC}"
else
    echo -e "${RED}❌ ERROR: No se pudo agregar la impresora${NC}"
    exit 1
fi

# Habilitar impresora
sudo cupsenable "${PRINTER_NAME}"
sudo cupsaccept "${PRINTER_NAME}"

# Establecer como predeterminada (opcional)
read -p "¿Establecer como impresora predeterminada? (s/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[SsYy]$ ]]; then
    lpoptions -d "${PRINTER_NAME}"
    echo -e "${GREEN}✓ Impresora establecida como predeterminada${NC}"
fi

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  ✓ INSTALACIÓN COMPLETADA${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${GREEN}La impresora '${PRINTER_ALIAS}' está lista para usar${NC}"
echo ""
echo -e "${YELLOW}Comandos útiles:${NC}"
echo "  Ver estado:       lpstat -p ${PRINTER_NAME}"
echo "  Imprimir prueba:  lp -d ${PRINTER_NAME} /usr/share/cups/data/testprint"
echo "  Listar trabajos:  lpq -P ${PRINTER_NAME}"
echo ""

# Listar impresoras instaladas
echo -e "${CYAN}Impresoras instaladas en este equipo:${NC}"
lpstat -p | sed 's/^/  /'
echo ""

exit 0
