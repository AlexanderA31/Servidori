#!/bin/bash
# ====================================================================
# Script para Compartir Impresora USB/Local con el Servidor Central
# Version 4.1 - Equivalente al script de Windows
# ====================================================================

set -e

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Configuración del servidor
SERVER_IP="10.1.16.31"
SERVER_PORT="8080"
LOG_FILE="/tmp/compartir-impresora.log"
CONFIG_DIR="$HOME/.config/PrinterShare"
CONFIG_FILE="$CONFIG_DIR/config.txt"

# Función para imprimir líneas decorativas
print_line() {
    echo -e "${CYAN}====================================================================${NC}"
}

# Función para imprimir encabezado
print_header() {
    echo -e "${CYAN}====================================================================${NC}"
    echo -e "${CYAN}${BOLD}  $1${NC}"
    echo -e "${CYAN}====================================================================${NC}"
}

# Verificar permisos de root
check_root() {
    if [ "$EUID" -ne 0 ]; then
        echo ""
        echo -e "${YELLOW}Solicitando permisos de administrador...${NC}"
        exec sudo "$0" "$@"
        exit $?
    fi
}

# Limpiar pantalla y mostrar banner
clear
echo ""
print_header "COMPARTIR IMPRESORA USB/LOCAL CON EL SERVIDOR"
echo ""
echo -e "  ${BOLD}Servidor:${NC} $SERVER_IP:$SERVER_PORT"
echo ""
print_line
echo ""

# Verificar si se ejecuta como root
check_root "$@"

# Obtener información de la computadora
HOSTNAME=$(hostname)
ORIGINAL_USER="${SUDO_USER:-$USER}"
USER_HOME=$(eval echo ~$ORIGINAL_USER)

# Obtener IP local
IP_ADDRESS=$(hostname -I | awk '{print $1}')

if [ -z "$IP_ADDRESS" ]; then
    echo -e "${RED}ERROR: No se pudo obtener la dirección IP${NC}"
    exit 1
fi

echo -e "${BOLD}Computadora:${NC} $HOSTNAME"
echo -e "${BOLD}Usuario:${NC} $ORIGINAL_USER"
echo -e "${BOLD}IP:${NC} $IP_ADDRESS"
echo ""
echo ""

# Verificar que CUPS esté instalado
if ! command -v lpstat &> /dev/null; then
    echo -e "${RED}ERROR: CUPS no está instalado${NC}"
    echo ""
    echo "Por favor, instala CUPS primero:"
    echo "  Debian/Ubuntu: sudo apt-get install cups"
    echo "  RedHat/Fedora: sudo yum install cups"
    exit 1
fi

# Listar impresoras locales/USB
echo "Buscando impresoras USB/Locales..."
echo ""

# Archivo temporal para almacenar impresoras
TEMP_PRINTERS="/tmp/printers_list_$$.txt"
trap "rm -f '$TEMP_PRINTERS'" EXIT

# Obtener impresoras locales usando lpstat
lpstat -p 2>/dev/null | awk '{print $2}' > "$TEMP_PRINTERS" || true

# Verificar si hay impresoras
if [ ! -s "$TEMP_PRINTERS" ]; then
    echo -e "${RED}ERROR: No se encontraron impresoras locales${NC}"
    echo ""
    echo "Asegúrate de que:"
    echo "  1. Hay una impresora USB conectada"
    echo "  2. CUPS está ejecutándose: sudo systemctl status cups"
    echo "  3. La impresora está configurada en CUPS"
    exit 1
fi

# Contar y mostrar impresoras
print_header "IMPRESORAS USB/LOCALES DISPONIBLES"
echo ""

declare -a PRINTER_NAMES
declare -a PRINTER_URIS
declare -a PRINTER_DRIVERS
PRINTER_COUNT=0

while IFS= read -r printer_name; do
    if [ -z "$printer_name" ]; then
        continue
    fi
    
    PRINTER_COUNT=$((PRINTER_COUNT + 1))
    PRINTER_NAMES[$PRINTER_COUNT]="$printer_name"
    
    # Obtener información adicional de la impresora
    printer_uri=$(lpstat -v "$printer_name" 2>/dev/null | grep -oP "(?<=device for $printer_name: ).*" || echo "N/A")
    printer_driver=$(lpoptions -p "$printer_name" 2>/dev/null | grep -oP "(?<=printer-make-and-model=').*?(?=')" || echo "N/A")
    
    PRINTER_URIS[$PRINTER_COUNT]="$printer_uri"
    PRINTER_DRIVERS[$PRINTER_COUNT]="$printer_driver"
    
    echo -e "${GREEN}[$PRINTER_COUNT]${NC} ${BOLD}$printer_name${NC}"
    echo "     Puerto: $printer_uri"
    echo "     Driver: $printer_driver"
    echo ""
done < "$TEMP_PRINTERS"

if [ $PRINTER_COUNT -eq 0 ]; then
    echo -e "${RED}ERROR: No se pudieron procesar las impresoras${NC}"
    exit 1
fi

# Seleccionar impresora
echo ""
read -p "Selecciona el número de la impresora (1-$PRINTER_COUNT): " SELECTION

# Validar selección
if ! [[ "$SELECTION" =~ ^[0-9]+$ ]] || [ "$SELECTION" -lt 1 ] || [ "$SELECTION" -gt $PRINTER_COUNT ]; then
    echo -e "${RED}ERROR: Selección inválida${NC}"
    exit 1
fi

SELECTED_PRINTER="${PRINTER_NAMES[$SELECTION]}"
SELECTED_PORT="${PRINTER_URIS[$SELECTION]}"
SELECTED_DRIVER="${PRINTER_DRIVERS[$SELECTION]}"

echo ""
print_header "IMPRESORA SELECCIONADA"
echo ""
echo -e "  ${BOLD}Nombre:${NC} $SELECTED_PRINTER"
echo -e "  ${BOLD}Puerto:${NC} $SELECTED_PORT"
echo -e "  ${BOLD}Driver:${NC} $SELECTED_DRIVER"
echo ""

# Crear nombre de alias para la impresora
PRINTER_ALIAS="${SELECTED_PRINTER}_${HOSTNAME}"

# Crear JSON para registro
TEMP_JSON="/tmp/printer_register_$$.json"
trap "rm -f '$TEMP_JSON'" EXIT

cat > "$TEMP_JSON" << EOF
{
  "alias": "$PRINTER_ALIAS",
  "model": "$SELECTED_DRIVER",
  "ip": "$IP_ADDRESS",
  "location": "Compartida-USB - $HOSTNAME - Usuario: $ORIGINAL_USER",
  "protocol": "IPP",
  "port": 631
}
EOF

# Registrar en el servidor
echo "Registrando impresora en el servidor..."
echo "URL: http://$SERVER_IP:$SERVER_PORT/api/register-shared-printer"
echo ""

TEMP_RESPONSE="/tmp/printer_response_$$.json"
trap "rm -f '$TEMP_RESPONSE'" EXIT

# Hacer petición HTTP usando curl
if ! curl -s -X POST \
    -H "Content-Type: application/json" \
    -d @"$TEMP_JSON" \
    --connect-timeout 15 \
    --max-time 30 \
    "http://$SERVER_IP:$SERVER_PORT/api/register-shared-printer" \
    -o "$TEMP_RESPONSE" 2>&1; then
    
    echo -e "${RED}ERROR: No se pudo conectar al servidor${NC}"
    echo ""
    echo "POSIBLES CAUSAS:"
    echo "  1. El servidor no está accesible en $SERVER_IP:$SERVER_PORT"
    echo "  2. Firewall bloqueando la conexión"
    echo "  3. Servidor de impresoras no está ejecutándose"
    echo ""
    exit 1
fi

if [ ! -s "$TEMP_RESPONSE" ]; then
    echo -e "${RED}ERROR: No se recibió respuesta del servidor${NC}"
    exit 1
fi

# Extraer puerto IPP de la respuesta
IPP_PORT=$(grep -oP '(?<="ippPort":)\s*\d+' "$TEMP_RESPONSE" | tr -d ' ' || echo "631")

echo -e "${GREEN}Respuesta del servidor recibida${NC}"
echo -e "${GREEN}Puerto IPP asignado: $IPP_PORT${NC}"
echo ""

# Compartir impresora via CUPS (IPP)
echo "Configurando compartición de impresora..."
echo ""

# Habilitar compartición en CUPS
if grep -q "^Browsing Off" /etc/cups/cupsd.conf 2>/dev/null; then
    sed -i 's/^Browsing Off/Browsing On/' /etc/cups/cupsd.conf
fi

# Permitir compartir impresoras
cupsctl --share-printers 2>/dev/null || true

# Compartir la impresora específica
lpadmin -p "$SELECTED_PRINTER" -o printer-is-shared=true 2>/dev/null

# Aceptar trabajos
cupsenable "$SELECTED_PRINTER" 2>/dev/null || true
cupsaccept "$SELECTED_PRINTER" 2>/dev/null || true

echo -e "${GREEN}[OK]${NC} Impresora compartida vía IPP/CUPS"
echo -e "      URL: http://$IP_ADDRESS:631/printers/$SELECTED_PRINTER"
echo ""

# Configurar firewall
echo "Configurando firewall para puerto $IPP_PORT..."
echo ""

# Detectar tipo de firewall
if command -v ufw &> /dev/null && ufw status | grep -q "Status: active"; then
    # UFW (Ubuntu/Debian)
    ufw allow $IPP_PORT/tcp comment "PrinterShare IPP" >/dev/null 2>&1
    ufw allow 631/tcp comment "CUPS IPP" >/dev/null 2>&1
    echo -e "${GREEN}[OK]${NC} Regla de firewall creada (ufw) para puerto $IPP_PORT"
    
elif command -v firewall-cmd &> /dev/null && systemctl is-active --quiet firewalld; then
    # Firewalld (RedHat/Fedora/CentOS)
    firewall-cmd --permanent --add-port=$IPP_PORT/tcp >/dev/null 2>&1
    firewall-cmd --permanent --add-service=ipp >/dev/null 2>&1
    firewall-cmd --reload >/dev/null 2>&1
    echo -e "${GREEN}[OK]${NC} Regla de firewall creada (firewalld) para puerto $IPP_PORT"
    
elif command -v iptables &> /dev/null; then
    # iptables
    iptables -A INPUT -p tcp --dport $IPP_PORT -j ACCEPT >/dev/null 2>&1
    iptables -A INPUT -p tcp --dport 631 -j ACCEPT >/dev/null 2>&1
    # Intentar guardar las reglas
    if command -v netfilter-persistent &> /dev/null; then
        netfilter-persistent save >/dev/null 2>&1
    elif [ -f /etc/sysconfig/iptables ]; then
        service iptables save >/dev/null 2>&1
    fi
    echo -e "${GREEN}[OK]${NC} Regla de firewall creada (iptables) para puerto $IPP_PORT"
else
    echo -e "${YELLOW}[AVISO]${NC} No se detectó firewall o no está activo"
fi

echo ""

# Reiniciar CUPS para aplicar cambios
systemctl restart cups 2>/dev/null || service cups restart 2>/dev/null || true

# Guardar configuración
mkdir -p "$CONFIG_DIR"

cat > "$CONFIG_FILE" << EOF
PRINTER_NAME=$SELECTED_PRINTER
SERVER_IP=$SERVER_IP
SERVER_PORT=$SERVER_PORT
IPP_PORT=$IPP_PORT
REGISTERED_AT=$(date)
EOF

# Ajustar permisos para el usuario original
chown -R "$ORIGINAL_USER:$(id -gn $ORIGINAL_USER)" "$CONFIG_DIR" 2>/dev/null || true

# Resumen final
echo ""
print_header "INFORMACIÓN DE CONEXIÓN"
echo ""
echo "  Otras computadoras pueden conectarse usando:"
echo ""
echo -e "  ${BOLD}Desde el Servidor de Impresoras:${NC}"
echo "     - La impresora aparecerá automáticamente en la tabla"
echo "     - Puerto IPP asignado: $IPP_PORT"
echo ""
echo -e "  ${BOLD}Conexión directa IPP (Linux/Windows/macOS):${NC}"
echo "     ipp://$IP_ADDRESS:631/printers/$SELECTED_PRINTER"
echo ""
echo -e "  ${BOLD}IMPORTANTE:${NC}"
echo "     - Esta computadora debe estar ENCENDIDA"
echo "     - La impresora debe estar CONECTADA"
echo "     - CUPS debe estar ejecutándose"
echo "     - No suspender la computadora"
echo ""
print_line
echo ""
print_header "CONFIGURACIÓN COMPLETADA EXITOSAMENTE"
echo ""
echo "La impresora USB ha sido compartida y registrada en el servidor."
echo "Otras computadoras pueden conectarse a través del servidor."
echo ""
echo "Para ver el estado, accede al panel de administración:"
echo "   http://$SERVER_IP:$SERVER_PORT/admin/printers"
echo ""
print_line
echo ""
echo -e "${GREEN}Presiona Enter para continuar...${NC}"
read -r

exit 0
